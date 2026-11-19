package live.dafeiyu;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** All authoritative state is guarded by Room. The serial outbox performs all I/O outside that lock.
 * A source adapter may acquire capture -> Room; no bridge code acquires capture from Room. */
@Service
public final class NativeMusicBridge {
  private static final System.Logger RELEASE_LOG=System.getLogger(NativeMusicBridge.class.getName());
  enum Mode { HTML, STOPPING_HTML, ACTIVATING_NATIVE, NATIVE, NATIVE_PAUSED, RELEASING, HOLDING_SILENCE }
  record Ticket(NativeMusicTransport.Request request,NativeMusicTransport.Prepared prepared,long generation,BooleanSupplier active,String taskId) {}
  private final LiveRoom room;private final boolean enabled;private final NativeMusicTransport transport;private final LongSupplier clock;
  private final String instanceId=UUID.randomUUID().toString();
  private final java.util.concurrent.Executor executor;private final ExecutorService ownedExecutor;
  private Mode mode=Mode.HTML;private long generation,revision,wireRevision,controlRevision,speechRevision;
  private String source="console-0",nonce="",notice="",speaking="";private long pulseAt,observationAt;
  private String speakingPlayback="";private long legacySpeechSequence;
  private record ClientSpeech(long sequence,boolean terminal){}
  private final LinkedHashMap<String,ClientSpeech> speechReceipts=new LinkedHashMap<>();
  private boolean desiredPaused,pumpQueued,closing,nativeCommitAttempted,releasePending;private CompletableFuture<Void> ready=new CompletableFuture<>();
  private final LinkedHashMap<String,String> requests=new LinkedHashMap<>();
  private NativeMusicTransport.Observation observation=new NativeMusicTransport.Observation(false,false,false,0,"",false);
  @Autowired public NativeMusicBridge(LiveRoom room,@Value("${music.provider:local}") String provider,@Value("${music.native-url:}") String url,@Value("${music.native-token-file:}") String tokenFile,ObjectMapper json){
    this(room,"qq-awoo".equals(provider),"qq-awoo".equals(provider)?new AwooNativeTransport(url,tokenFile,json):null,System::currentTimeMillis,null);
  }
  NativeMusicBridge(LiveRoom room,boolean enabled,NativeMusicTransport transport,LongSupplier clock,java.util.concurrent.Executor executor){
    this.room=room;this.enabled=enabled;this.transport=transport;this.clock=clock;
    wireRevision=controlRevision=speechRevision=Math.max(1,clock.getAsLong())*1000;
    if(executor==null){ownedExecutor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),r->Thread.ofPlatform().daemon().name("qq-native-outbox").unstarted(r));this.executor=ownedExecutor;}else{this.executor=executor;ownedExecutor=null;}
    room.nativeMusic(this);
    if(enabled)synchronized(room){mode=Mode.RELEASING;notice="启动时核对旧QQ执行租约和暂停状态";long ticket=++generation;String capturedOwner=owner(),capturedSource=source;submit(()->releaseWire(ticket,capturedOwner,capturedSource));}
  }
  boolean enabled(){return enabled;}
  boolean blocksHtml(){synchronized(room){return enabled&&mode!=Mode.HTML;}}
  private String owner(){String value=room.ownerEpoch();return value.isBlank()?"none":value;}
  private void publish(){revision++;room.nativeMusicUpdate(status());}
  public Map<String,Object> status(){synchronized(room){
    var reportedAt=observationAt;
    var value=new LinkedHashMap<String,Object>();value.put("enabled",enabled);value.put("instanceId",instanceId);value.put("mode",mode.name());value.put("revision",revision);value.put("ownerEpoch",room.ownerEpoch());value.put("stopNonce",nonce);value.put("notice",notice);value.put("requests",new LinkedHashMap<>(requests));
    value.put("playbackConfirmed",(mode==Mode.NATIVE)&&observationAt>0&&clock.getAsLong()-observationAt<5000&&observation.playbackConfirmed());value.put("reportedAt",reportedAt);value.put("completeSongVerified",false);value.put("previewStatus","unknown");value.put("position",observation.position());value.put("duration",observation.duration());value.put("current",Map.of("id",observation.trackId(),"title",observation.title(),"artist",observation.artist()));value.put("queue",observation.queue());value.put("speaking",!speaking.isEmpty());return value;
  }}
  private void submit(Runnable operation){
    try{executor.execute(()->{if(Thread.holdsLock(room))throw new IllegalStateException("native I/O under Room");operation.run();});}
    catch(RejectedExecutionException full){if(closing)return;mode=Mode.HOLDING_SILENCE;notice="原生音乐操作积压，保持网页音乐停止";publish();}
  }
  private void requireOwner(String client,String epoch){if(!room.isOwner(client,epoch))throw new ProviderException("NOT_ACTIVE_MUSIC_OWNER",409);}
  private CompletableFuture<Void> requestNative(BooleanSupplier active){synchronized(room){
    if(!enabled)throw new ProviderException("QQ_BRIDGE_DISABLED",409);
    if(room.ownerEpoch().isBlank())throw new ProviderException("LIVE_OWNER_REQUIRED",409);
    if(!active.getAsBoolean())throw new ProviderException("QQ_REQUEST_EXPIRED",409);
    // Missing startup pause evidence never releases HTML. Before any native COMMIT
    // attempt, an explicit request may instead establish a fresh HTML-stop barrier.
    boolean startupRecovery=!nativeCommitAttempted&&(mode==Mode.HOLDING_SILENCE||mode==Mode.RELEASING);
    if(mode==Mode.HTML||startupRecovery){
      releasePending=false;
      if(startupRecovery){generation++;ready.completeExceptionally(new ProviderException("QQ_REQUEST_EXPIRED",409));}
      mode=Mode.STOPPING_HTML;nonce=UUID.randomUUID().toString();ready=new CompletableFuture<>();notice="等待主播页面实际停止网页音乐";publish();
    }
    if(mode==Mode.HOLDING_SILENCE||mode==Mode.RELEASING)throw new ProviderException("QQ_RELEASE_NOT_CONFIRMED",409);
    return ready;
  }}
  public boolean htmlStopped(String client,String epoch,String stopNonce,boolean stopped){synchronized(room){
    requireOwner(client,epoch);if(mode!=Mode.STOPPING_HTML||!nonce.equals(stopNonce))return false;
    if(!stopped){mode=Mode.HOLDING_SILENCE;notice="网页音频未确认停止，未启动QQ";ready.completeExceptionally(new ProviderException("HTML_AUDIO_NOT_STOPPED",409));publish();return false;}
    mode=Mode.ACTIVATING_NATIVE;long ticket=generation;String capturedOwner=owner(),capturedSource=source;publish();
    submit(()->{
      try{
        synchronized(room){if(!same(ticket,capturedOwner,capturedSource)||mode!=Mode.ACTIVATING_NATIVE)return;}
        transport.context(nextWire(),capturedOwner,capturedSource,clock.getAsLong()+9000,true);
        synchronized(room){if(!same(ticket,capturedOwner,capturedSource)||mode!=Mode.ACTIVATING_NATIVE)return;mode=Mode.NATIVE;notice="QQ执行入口已就绪，实际播放另行确认";ready.complete(null);publish();}
      }catch(RuntimeException failure){hold(ticket,"QQ执行入口未确认，保持网页音乐停止");}
    });return true;
  }}
  private synchronized long nextWire(){return ++wireRevision;}
  private boolean same(long ticket,String capturedOwner,String capturedSource){return ticket==generation&&owner().equals(capturedOwner)&&source.equals(capturedSource);}
  private void hold(long ticket,String message){synchronized(room){if(ticket!=generation)return;mode=Mode.HOLDING_SILENCE;notice=message;ready.completeExceptionally(new ProviderException("QQ_BRIDGE_UNCONFIRMED",409));publish();}}
  void source(String next){synchronized(room){if(!enabled||source.equals(next))return;source=next;invalidate("采集来源已变化");}}
  void ownerChanged(){synchronized(room){if(enabled)invalidate("主播会话已变化");}}
  private void invalidate(String message){
    generation++;controlRevision++;releasePending=false;ready.completeExceptionally(new ProviderException("QQ_REQUEST_EXPIRED",409));speaking="";speakingPlayback="";speechReceipts.clear();speechRevision++;observation=new NativeMusicTransport.Observation(false,false,false,0,"",false);
    requests.replaceAll((id,phase)->Set.of("PREPARING","PREPARED","COMMITTED","QUEUED").contains(phase)?"INVALIDATED":phase);
    if(mode==Mode.HTML){notice=message;publish();return;}
    mode=Mode.RELEASING;notice=message+"，等待QQ暂停和音量恢复";long ticket=generation;String capturedOwner=owner(),capturedSource=source;publish();
    submit(()->releaseWire(ticket,capturedOwner,capturedSource));
  }
  private void releaseWire(long ticket,String capturedOwner,String capturedSource){
    long control;synchronized(room){if(!same(ticket,capturedOwner,capturedSource)||mode!=Mode.RELEASING)return;control=controlRevision;}
    long wire=nextWire();String phase="inactive_context_ack";
    try{
      transport.context(wire,capturedOwner,capturedSource,clock.getAsLong()+9000,false);
      // Only a successful inactive/reset ACK permits later read-only release confirmation.
      synchronized(room){if(!same(ticket,capturedOwner,capturedSource)||control!=controlRevision||mode!=Mode.RELEASING)return;releasePending=true;}
      phase="fresh_pause_observation";var observed=transport.observe();
      synchronized(room){if(currentObservation(ticket,capturedOwner,capturedSource,control,true))applyReleaseObservation(observed);}
    }catch(RuntimeException failure){
      String code=failure instanceof ProviderException known?known.code:"QQ_RELEASE_UNCONFIRMED";
      RELEASE_LOG.log(System.Logger.Level.WARNING,"QQ release failed: phase={0} code={1} wireRevision={2} generation={3}",phase,code,wire,ticket);
      synchronized(room){if(same(ticket,capturedOwner,capturedSource)&&control==controlRevision&&Set.of(Mode.RELEASING,Mode.HOLDING_SILENCE).contains(mode))hold(ticket,"QQ释放或音量恢复尚未确认，网页音乐保持停止");}
    }
  }
  private boolean currentObservation(long ticket,String capturedOwner,String capturedSource,long control,boolean releasing){
    return same(ticket,capturedOwner,capturedSource)&&control==controlRevision&&(releasing?releasePending&&Set.of(Mode.RELEASING,Mode.HOLDING_SILENCE).contains(mode):Set.of(Mode.NATIVE,Mode.NATIVE_PAUSED).contains(mode));
  }
  private void applyReleaseObservation(NativeMusicTransport.Observation observed){
    if(!observed.pauseConfirmed()){mode=Mode.HOLDING_SILENCE;notice="QQ暂停尚未确认，网页音乐保持停止";}else{mode=Mode.HTML;nonce="";desiredPaused=false;releasePending=false;notice="QQ暂停及降音清理已确认，可恢复网页音乐";ready=new CompletableFuture<>();}
    observation=observed;observationAt=clock.getAsLong();publish();
  }
  public void release(){synchronized(room){if(enabled)invalidate("正在释放原生音乐");}}
  Ticket prepare(String eventId,long observedAt,String platform,String uid,String name,AiDecision decision,BooleanSupplier active,String taskId){
    if(Thread.holdsLock(room))throw new IllegalStateException("prepare must run outside Room");
    if(!decision.requestsMusic()||decision.query().isBlank())throw new IllegalArgumentException("请补充歌曲名称");
    CompletableFuture<Void> prepared=requestNative(active);
    try{prepared.get(8,TimeUnit.SECONDS);}catch(Exception unavailable){throw new ProviderException("QQ_WAITING_FOR_HTML_STOP",409);}
    NativeMusicTransport.Request request;long ticket;
    synchronized(room){
      if(!active.getAsBoolean()||!Set.of(Mode.NATIVE,Mode.NATIVE_PAUSED).contains(mode))throw new ProviderException("QQ_REQUEST_EXPIRED",409);
      ticket=generation;String id=UUID.randomUUID().toString();String safeEvent="event-"+UUID.nameUUIDFromBytes(eventId.getBytes(java.nio.charset.StandardCharsets.UTF_8));request=new NativeMusicTransport.Request(id,safeEvent,observedAt,platform,owner(),source,decision.query(),decision.artist(),uid,InputFilter.clean(name,30));requests.put(id,"PREPARING");trim();publish();
    }
    NativeMusicTransport.Prepared found;
    try{found=transport.prepare(request);}catch(RuntimeException failure){
      cancelOutside(request.requestId());
      synchronized(room){
        if(!same(ticket,request.ownerEpoch(),request.sourceEpoch())||!active.getAsBoolean()){
          requests.put(request.requestId(),"INVALIDATED");publish();throw new ProviderException("QQ_REQUEST_EXPIRED",409);
        }
        requests.put(request.requestId(),"REJECTED");if(failure instanceof ProviderException rejected)notice=NativeSongFailure.message(rejected,notice);publish();
      }
      throw failure;
    }
    synchronized(room){
      if(!same(ticket,request.ownerEpoch(),request.sourceEpoch())||!active.getAsBoolean()){requests.put(request.requestId(),"INVALIDATED");submit(()->cancelOutside(request.requestId()));throw new ProviderException("QQ_REQUEST_EXPIRED",409);}
      requests.put(request.requestId(),"PREPARED");publish();return new Ticket(request,found,ticket,active,taskId);
    }
  }
  /** Linearization point: capture caller retains its source lock, then this Room lock.
   * If cancellation/rotation wins first, no COMMIT enters the private outbox. */
  String commit(Ticket ticket){synchronized(room){
    var request=ticket.request();
    if(!same(ticket.generation(),request.ownerEpoch(),request.sourceEpoch())||!ticket.active().getAsBoolean()||!"PREPARED".equals(requests.get(request.requestId()))){requests.put(request.requestId(),"INVALIDATED");publish();submit(()->cancelOutside(request.requestId()));return "这条点歌已过期，没有加入队列。";}
    requests.put(request.requestId(),"COMMITTED");publish();
    submit(()->{
      synchronized(room){if(!currentCommit(ticket)){discardCommit(ticket);return;}}
      long deadline=System.nanoTime()+NativeMusicTransport.COMMIT_BUDGET_NANOS;
      try{
        // Renewal and every POST/GET share six seconds; the active lease remains nine.
        transport.context(nextWire(),request.ownerEpoch(),request.sourceEpoch(),clock.getAsLong()+9000,true);
        synchronized(room){if(!currentCommit(ticket)){discardCommit(ticket);return;}nativeCommitAttempted=true;}
        transport.commit(ticket.prepared(),deadline);
        synchronized(room){if(!currentCommit(ticket)){discardCommit(ticket);return;}requests.put(request.requestId(),"QUEUED");notice="已进入QQ正常队列，实际播放另行确认";publish();}
      }catch(RuntimeException failure){synchronized(room){
        if(!currentCommit(ticket)){discardCommit(ticket);return;}
        boolean rejected=failure instanceof ProviderException error&&NativeSongFailure.definitive(error);
        if(rejected)requests.put(request.requestId(),"REJECTED");
        String fallback=rejected?"QQ点歌请求已被拒绝，网页音乐保持停止":"QQ提交结果未知，保持网页音乐停止";
        hold(ticket.generation(),failure instanceof ProviderException error?NativeSongFailure.message(error,fallback):fallback);
      }}
    });return "已选定《"+ticket.prepared().title()+"》，正在提交QQ正常点歌；实际播放以反馈为准。";
  }}
  private boolean currentCommit(Ticket ticket){
    var request=ticket.request();return !closing&&same(ticket.generation(),request.ownerEpoch(),request.sourceEpoch())&&ticket.active().getAsBoolean()&&"COMMITTED".equals(requests.get(request.requestId()))&&Set.of(Mode.NATIVE,Mode.NATIVE_PAUSED).contains(mode);
  }
  private void discardCommit(Ticket ticket){
    String id=ticket.request().requestId();if("COMMITTED".equals(requests.get(id))){requests.put(id,"INVALIDATED");publish();}cancelOutsideLater(id);
  }
  void cancelPrepared(Ticket ticket){synchronized(room){requests.put(ticket.request().requestId(),"INVALIDATED");submit(()->cancelOutside(ticket.request().requestId()));publish();}}
  private void cancelOutsideLater(String id){submit(()->cancelOutside(id));}
  private void cancelOutside(String id){try{transport.cancel(id);}catch(RuntimeException ignored){/* Owner/source leases still expire; never enable HTML on this basis. */}}
  String console(AiDecision decision,BooleanSupplier active,String eventId,String taskId){return commit(prepare(eventId,clock.getAsLong(),"local-console","ds-console","主播本机",decision,active,taskId));}
  public Map<String,Object> consoleRequest(String command,String artist){
    if(command==null||!command.strip().matches("^点歌\\s+\\S[\\s\\S]*"))throw new IllegalArgumentException("请输入点歌和歌曲名称");
    String query=InputFilter.clean(command.strip().replaceFirst("^点歌\\s+",""),60),performer=InputFilter.clean(artist,30);
    return Map.of("notice",console(new AiDecision("music_request",query,performer,""),()->true,"console-"+UUID.randomUUID(),""),"native",status());
  }
  public Object pause(boolean paused){synchronized(room){
    if(!Set.of(Mode.NATIVE,Mode.NATIVE_PAUSED).contains(mode))throw new ProviderException("QQ_EXECUTOR_NOT_READY",409);
    releasePending=false;desiredPaused=paused;long control=++controlRevision,ticket=generation;String capturedOwner=owner(),capturedSource=source;notice=paused?"等待QQ暂停确认":"等待QQ恢复反馈";publish();
    submit(()->{
      synchronized(room){if(!same(ticket,capturedOwner,capturedSource)||control!=controlRevision)return;}
      try{transport.pause(control,capturedOwner,capturedSource,paused);var observed=transport.observe();synchronized(room){if(!same(ticket,capturedOwner,capturedSource)||control!=controlRevision)return;observation=observed;observationAt=clock.getAsLong();if(paused&&!observed.pauseConfirmed()){notice="QQ暂停尚未确认，网页音乐保持停止";}else{mode=paused?Mode.NATIVE_PAUSED:Mode.NATIVE;notice=paused?"QQ已暂停":"QQ恢复已提交，实际播放等待反馈";}publish();}}
      catch(RuntimeException failure){synchronized(room){if(currentObservation(ticket,capturedOwner,capturedSource,control,false))hold(ticket,"QQ暂停/恢复未确认，网页音乐保持停止");}}
    });return status();
  }}
  boolean speech(String client,String epoch,String taskId,String phase){synchronized(room){return speech(client,epoch,nonce,taskId,"legacy-"+taskId,++legacySpeechSequence,phase);}}
  public boolean speech(String client,String epoch,String executionId,String taskId,String playbackId,long sequence,String phase){synchronized(room){
    requireOwner(client,epoch);if(!enabled||!Set.of(Mode.NATIVE,Mode.NATIVE_PAUSED).contains(mode))return false;
    if(!nonce.equals(executionId)||sequence<1)return false;
    String key=taskId+":"+playbackId;ClientSpeech previous=speechReceipts.get(key);
    if(previous!=null&&(previous.terminal()||sequence<=previous.sequence()))return false;
    if(!room.isCurrentSpeech(taskId)&&previous==null)return false;
    if("START".equals(phase)){
      if(!room.isCurrentSpeech(taskId)||!room.audioEnabled())return false;
      if(!speaking.equals(taskId)||!speakingPlayback.equals(key)){
        if(!speaking.isEmpty())queueSpeech(false,speaking);
        speaking=taskId;speakingPlayback=key;queueSpeech(true,taskId);
      }
      pulseAt=clock.getAsLong();
    }else if("PULSE".equals(phase)){
      if(!speaking.equals(taskId)||!speakingPlayback.equals(key)||!room.isCurrentSpeech(taskId))return false;pulseAt=clock.getAsLong();
    }else if("END".equals(phase)){
      if(speaking.equals(taskId)&&speakingPlayback.equals(key)){speaking="";speakingPlayback="";queueSpeech(false,taskId);}
    }else return false;
    // Only accepted events advance the sequence; an early PULSE must not fence START.
    speechReceipts.put(key,new ClientSpeech(sequence,"END".equals(phase)));while(speechReceipts.size()>1000)speechReceipts.remove(speechReceipts.keySet().iterator().next());
    publish();return true;
  }}
  void speechEnded(String taskId){synchronized(room){if(enabled&&speaking.equals(taskId)){if(!speakingPlayback.isEmpty())speechReceipts.put(speakingPlayback,new ClientSpeech(Long.MAX_VALUE,true));speaking="";speakingPlayback="";queueSpeech(false,taskId);publish();}}}
  private void queueSpeech(boolean active,String taskId){
    long speech=++speechRevision,ticket=generation;String capturedOwner=owner(),capturedSource=source;
    submit(()->{
      synchronized(room){if(!same(ticket,capturedOwner,capturedSource)||active&&(speech!=speechRevision||!speaking.equals(taskId)||!room.isCurrentSpeech(taskId)))return;}
      try{transport.speech(speech,capturedOwner,capturedSource,taskId,active,clock.getAsLong()+8000);}
      catch(RuntimeException failure){synchronized(room){if(ticket==generation){notice="原生音乐降音/恢复未确认";publish();}}}
    });
  }
  @Scheduled(fixedDelay=2000) public void pump(){synchronized(room){
    if(!enabled||pumpQueued)return;
    if(!speaking.isEmpty()&&(clock.getAsLong()-pulseAt>4500||!room.isCurrentSpeech(speaking))){String previous=speaking;speaking="";speakingPlayback="";queueSpeech(false,previous);}
    boolean releasing=releasePending&&Set.of(Mode.RELEASING,Mode.HOLDING_SILENCE).contains(mode);
    if(!releasing&&!Set.of(Mode.NATIVE,Mode.NATIVE_PAUSED).contains(mode))return;
    pumpQueued=true;long ticket=generation,control=controlRevision;String capturedOwner=owner(),capturedSource=source;
    submit(()->{try{
      synchronized(room){if(!currentObservation(ticket,capturedOwner,capturedSource,control,releasing))return;}
      if(!releasing)transport.context(nextWire(),capturedOwner,capturedSource,clock.getAsLong()+9000,true);
      var observed=transport.observe();
      synchronized(room){if(!currentObservation(ticket,capturedOwner,capturedSource,control,releasing))return;if(releasing){applyReleaseObservation(observed);return;}observation=observed;observationAt=clock.getAsLong();if(desiredPaused&&observed.pauseConfirmed()){mode=Mode.NATIVE_PAUSED;notice="QQ已暂停";}if(!speaking.isEmpty()&&clock.getAsLong()-pulseAt<=4500)queueSpeech(true,speaking);publish();}
    }catch(RuntimeException failure){synchronized(room){if(currentObservation(ticket,capturedOwner,capturedSource,control,releasing))hold(ticket,"QQ租约/观察未确认，网页音乐保持停止");}}finally{synchronized(room){pumpQueued=false;}}});
  }}
  private void trim(){while(requests.size()>200)requests.remove(requests.keySet().iterator().next());}
  @jakarta.annotation.PreDestroy public void close(){
    if(ownedExecutor==null)return;
    synchronized(room){if(closing)return;closing=true;if(enabled)invalidate("服务关闭，正在释放QQ");}
    ownedExecutor.shutdown();
    // A bounded six-second submit may precede inactive context + observe (2.5s each).
    if(!Thread.holdsLock(room))try{if(!ownedExecutor.awaitTermination(12,TimeUnit.SECONDS))ownedExecutor.shutdownNow();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();ownedExecutor.shutdownNow();}
  }
}
