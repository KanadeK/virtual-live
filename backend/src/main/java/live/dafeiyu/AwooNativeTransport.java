package live.dafeiyu;

import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only v2 client; rejects redirects and never prints remote bodies or credentials. */
final class AwooNativeTransport implements NativeMusicTransport {
  private final URI base;private final String token;private final ObjectMapper json;
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
  AwooNativeTransport(String url,String tokenFile,ObjectMapper json){
    try{
      base=URI.create(url);this.json=json;
      if(!"http".equals(base.getScheme())||!"127.0.0.1".equals(base.getHost())||base.getPort()<1024||base.getPort()>65535||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!(base.getPath().isEmpty()||base.getPath().equals("/")))throw new IllegalArgumentException();
      Path file=Path.of(tokenFile);if(!file.isAbsolute()||Files.size(file)>256)throw new IllegalArgumentException();
      token=Files.readString(file).strip();if(!token.matches("[A-Za-z0-9_-]{43,128}"))throw new IllegalArgumentException();
    }catch(Exception invalid){throw new IllegalArgumentException("QQ桥接需要固定本机地址和独立私有令牌文件");}
  }
  private JsonNode call(String method,String path,Object body){
    return call(method,path,body,0);
  }
  /** Completion includes the bounded body, not only response headers. */
  private JsonNode call(String method,String path,Object body,long commitDeadline){
    return call(method,path,body,commitDeadline,2_500_000_000L);
  }
  private JsonNode call(String method,String path,Object body,long commitDeadline,long callBudget){
    CompletableFuture<HttpResponse<byte[]>> pending=null;
    boolean finalWindow=false;
    try{
      long now=System.nanoTime();long remaining=commitDeadline==0?callBudget:Math.min(callBudget,commitDeadline-now);
      finalWindow=commitDeadline!=0&&commitDeadline-now<=2_500_000_000L;
      if(remaining<=0)throw new TimeoutException();
      long callDeadline=now+remaining;
      var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofNanos(remaining)).header("Authorization","Bearer "+token).header("Content-Type","application/json");
      builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      pending=client.sendAsync(builder.build(),info->new BoundedBody());
      long wait=callDeadline-System.nanoTime();if(wait<=0)throw new TimeoutException();
      var response=pending.get(wait,TimeUnit.NANOSECONDS);
      if(System.nanoTime()>=callDeadline)throw new TimeoutException();
      JsonNode result=json.readTree(response.body());if(result==null||!result.isObject())throw new IllegalStateException();
      if(response.statusCode()<200||response.statusCode()>=300)throw bridgeFailure(result);
      if(commitDeadline!=0&&System.nanoTime()>=commitDeadline)throw new TimeoutException();return result;
    }catch(ProviderException known){throw known;}
    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ProviderException("QQ_BRIDGE_INTERRUPTED",503);}
    catch(TimeoutException timeout){if(finalWindow)throw new ProviderException("QQ_QUEUE_COMMIT_UNCONFIRMED",409);throw new ProviderException("QQ_BRIDGE_UNAVAILABLE",503);}
    catch(ExecutionException failure){if(finalWindow&&failure.getCause() instanceof HttpTimeoutException)throw new ProviderException("QQ_QUEUE_COMMIT_UNCONFIRMED",409);throw new ProviderException("QQ_BRIDGE_UNAVAILABLE",503);}
    catch(Exception unavailable){throw new ProviderException("QQ_BRIDGE_UNAVAILABLE",503);}
    finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
  }
  private static ProviderException bridgeFailure(JsonNode result){
    String code=switch(result.path("error").asText()){
      case "native_release_queue_guard_unconfirmed"->"QQ_RELEASE_QUEUE_GUARD_UNCONFIRMED";
      case "native_release_target_timeout"->"QQ_RELEASE_TARGET_TIMEOUT";
      case "native_release_target_read_failed"->"QQ_RELEASE_TARGET_READ_FAILED";
      case "native_release_target_changed"->"QQ_RELEASE_TARGET_CHANGED";
      case "native_release_pause_unconfirmed"->"QQ_RELEASE_PAUSE_UNCONFIRMED";
      case "native_volume_restore_unconfirmed"->"QQ_RELEASE_VOLUME_UNCONFIRMED";
      case "native_release_reset_unconfirmed"->"QQ_RELEASE_RESET_UNCONFIRMED";
      case "native_release_unconfirmed"->"QQ_RELEASE_UNCONFIRMED";
      default->"QQ_BRIDGE_UNAVAILABLE";
    };
    return new ProviderException(code,503);
  }
  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body=new CompletableFuture<>();
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody(){return body;}
    public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
    public void onNext(List<ByteBuffer> chunks){
      if(body.isDone())return;
      long incoming=chunks.stream().mapToLong(ByteBuffer::remaining).sum();
      if(bytes.size()+incoming>32768){subscription.cancel();body.completeExceptionally(new IllegalStateException("bridge_body_limit"));return;}
      for(var chunk:chunks){byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);}
      subscription.request(1);
    }
    public void onError(Throwable error){body.completeExceptionally(error);}
    public void onComplete(){body.complete(bytes.toByteArray());}
  }
  public void context(long revision,String owner,String source,long expiresAt,boolean active){
    // Release includes guard cancellation, fresh target sampling, Pause and native-volume reset.
    // Other requests and commit deadlines retain their existing 2.5-second call limit.
    var response=call("PUT","/v1/context",Map.of("revision",revision,"ownerEpoch",owner,"sourceEpoch",source,"expiresAt",expiresAt,"htmlAudioStopped",active,"active",active),0,active?2_500_000_000L:20_000_000_000L);
    if(!response.path("active").isBoolean()||response.path("active").asBoolean()!=active||response.path("revision").asLong(-1)!=revision)throw new ProviderException("QQ_EXECUTOR_NOT_READY",409);
  }
  public Prepared prepare(Request request){
    var payload=new LinkedHashMap<String,Object>();payload.put("v",1);payload.put("requestId",request.requestId());payload.put("eventId",request.eventId());payload.put("observedAt",request.observedAt());payload.put("source",request.source());payload.put("ownerEpoch",request.ownerEpoch());payload.put("sourceEpoch",request.sourceEpoch());payload.put("query",request.query());payload.put("artist",request.artist());
    payload.put("viewer",Map.of("uid",request.uid(),"name",request.name(),"guardLevel",0,"isManager",false,"medalLevel",0));
    JsonNode state=call("POST","/v2/requests/prepare",payload);long deadline=System.nanoTime()+Duration.ofSeconds(12).toNanos();
    while(System.nanoTime()<deadline){
      if("prepared".equals(state.path("stage").asText())){
        var track=state.path("track");String reservation=state.path("reservation").asText();
        if(!reservation.matches("[A-Za-z0-9:_-]{1,120}")||track.path("id").asText().isBlank())break;
        return new Prepared(request.requestId(),reservation,track.path("id").asText(),track.path("title").asText(),track.path("artist").asText());
      }
      if("rejected".equals(state.path("stage").asText()))throw NativeSongFailure.rejected(state.path("errorCode").asText());
      if(Set.of("cancelled","expired").contains(state.path("stage").asText()))throw new ProviderException("QQ_NORMAL_REQUEST_REJECTED",409);
      try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ProviderException("QQ_BRIDGE_INTERRUPTED",503);}
      state=call("GET","/v2/requests/"+request.requestId(),null);
    }
    throw new ProviderException("QQ_PREPARATION_TIMEOUT",504);
  }
  public void commit(Prepared prepared){
    commit(prepared,System.nanoTime()+COMMIT_BUDGET_NANOS);
  }
  public void commit(Prepared prepared,long deadline){
    JsonNode receipt=call("POST","/v2/requests/"+prepared.requestId()+"/commit",Map.of("reservation",prepared.reservation()),deadline);
    while(System.nanoTime()<deadline){
      String stage=receipt.path("stage").asText();if(stage.equals("queued"))return;
      if(stage.equals("rejected"))throw NativeSongFailure.rejected(receipt.path("errorCode").asText());
      if(Set.of("cancelled","expired").contains(stage))throw new ProviderException("QQ_NORMAL_REQUEST_REJECTED",409);
      if(!stage.isEmpty()&&!Set.of("prepared","committing","waiting").contains(stage))break;
      receipt=call("GET","/v2/requests/"+prepared.requestId(),null,deadline);
      // Parse terminal replies before another delay or request.
      stage=receipt.path("stage").asText();if(stage.equals("queued"))return;
      if(stage.equals("rejected"))throw NativeSongFailure.rejected(receipt.path("errorCode").asText());
      if(Set.of("cancelled","expired").contains(stage))throw new ProviderException("QQ_NORMAL_REQUEST_REJECTED",409);
      if(!Set.of("prepared","committing","waiting").contains(stage))break;
      long sleep=Math.min(25_000_000L,deadline-System.nanoTime());
      try{if(sleep>0)TimeUnit.NANOSECONDS.sleep(sleep);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ProviderException("QQ_BRIDGE_INTERRUPTED",503);}
    }
    throw new ProviderException("QQ_QUEUE_COMMIT_UNCONFIRMED",409);
  }
  public void cancel(String requestId){call("POST","/v2/requests/"+requestId+"/cancel",Map.of());}
  public Observation observe(){
    JsonNode state=call("GET","/v2/state",null),nativeState=state.path("state"),evidence=nativeState.path("nativeEvidence");
    var current=nativeState.path("snapshot").path("current");var queue=new ArrayList<Track>();for(JsonNode item:nativeState.path("queue")){if(queue.size()>=20)break;queue.add(new Track(item.path("requestId").asText(),item.path("id").asText(),InputFilter.clean(item.path("title").asText(),80),InputFilter.clean(item.path("artist").asText(),80)));}
    return new Observation(evidence.path("pauseConfirmed").asBoolean(false),evidence.path("playbackConfirmed").asBoolean(false),false,evidence.path("reportedPosition").asDouble(0),current.path("id").asText(),state.path("paused").asBoolean(false),InputFilter.clean(current.path("title").asText(),80),InputFilter.clean(current.path("artist").asText(),80),evidence.path("duration").asDouble(0),List.copyOf(queue));
  }
  public void pause(long revision,String owner,String source,boolean paused){call("POST","/v2/playback",Map.of("revision",revision,"ownerEpoch",owner,"sourceEpoch",source,"paused",paused));}
  public void speech(long revision,String owner,String source,String taskId,boolean active,long expiresAt){call("POST","/v2/speech",Map.of("revision",revision,"ownerEpoch",owner,"sourceEpoch",source,"taskId",taskId,"active",active,"expiresAt",expiresAt));}
}
