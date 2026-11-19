package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static live.dafeiyu.NativeMusicBridgeTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Composition check: real cold state machine + actual v2 rejection parser, entirely in memory. */
class NativeMusicRuntimeRepairsTest {
  @TempDir Path scratch;
  static final class Transport implements NativeMusicTransport {
    final LiveRoom room;final AwooNativeTransport parser;final List<String> calls=new CopyOnWriteArrayList<>();
    volatile boolean reject=true;
    Transport(LiveRoom room,AwooNativeTransport parser){this.room=room;this.parser=parser;}
    void outside(){assertFalse(Thread.holdsLock(room));}
    public void context(long revision,String owner,String source,long expiry,boolean active){outside();calls.add("context:"+active);}
    public Observation observe(){outside();calls.add("observe");return new Observation(false,false,false,0,"",false);}
    public Prepared prepare(Request request){outside();calls.add("prepare");return reject?parser.prepare(request):new Prepared(request.requestId(),"reservation","qq-one",request.query(),request.artist());}
    public void commit(Prepared prepared){outside();calls.add("commit");}
    public void cancel(String requestId){outside();calls.add("cancel");}
    public void pause(long revision,String owner,String source,boolean paused){throw new AssertionError("unexpected pause");}
    public void speech(long revision,String owner,String source,String task,boolean active,long expiry){throw new AssertionError("unexpected speech");}
    long count(String call){return calls.stream().filter(call::equals).count();}
  }
  static final class Fixture implements AutoCloseable {
    final AtomicLong clock=new AtomicLong(1000);final LiveRoom room=new LiveRoom(new MockAiReplyService(),clock::get);
    final ManualExecutor executor=new ManualExecutor();final NativeSongFailureTest.MemoryClient client;
    final Transport transport;final NativeMusicBridge bridge;
    final List<FutureTask<NativeMusicBridge.Ticket>> work=new ArrayList<>();
    Fixture(Path scratch,String errorCode)throws Exception {
      room.subscribe("owner",true);Path token=scratch.resolve("fake-token.txt");Files.writeString(token,"x".repeat(48));
      var json=new ObjectMapper();client=new NativeSongFailureTest.MemoryClient(json.writeValueAsString(Map.of("stage","rejected","errorCode",errorCode,"message","untrusted private remote detail")));
      var parser=new AwooNativeTransport("http://127.0.0.1:17630",token.toString(),json);
      var field=AwooNativeTransport.class.getDeclaredField("client");field.setAccessible(true);field.set(parser,client);
      transport=new Transport(room,parser);bridge=new NativeMusicBridge(room,true,transport,clock::get,executor);executor.drain();
    }
    String mode(){return String.valueOf(bridge.status().get("mode"));}
    String nonce(){return String.valueOf(bridge.status().get("stopNonce"));}
    FutureTask<NativeMusicBridge.Ticket> request(){
      var future=new FutureTask<>(()->bridge.prepare(UUID.randomUUID().toString(),clock.get(),"local-console","ds-console","主播",SONG,()->true,""));
      work.add(future);Thread.ofPlatform().daemon().start(future);return future;
    }
    void acknowledge()throws Exception {
      until(()->mode().equals("STOPPING_HTML"));assertTrue(bridge.htmlStopped("owner",room.ownerEpoch("owner"),nonce(),true));executor.drain();
    }
    ProviderException rejection(FutureTask<?> future)throws Exception {
      var error=assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));return assertInstanceOf(ProviderException.class,error.getCause());
    }
    public void close(){work.forEach(future->future.cancel(true));room.shutdown();}
  }

  @ParameterizedTest
  @ValueSource(strings={"ambiguous_song","qq_metadata_unavailable"})
  void coldAckThenClassifiedRejectionCanRetryNormallyButNeverReleasesUnknownHtml(String category)throws Exception {
    try(var f=new Fixture(scratch,category)){
      assertEquals("HOLDING_SILENCE",f.mode());assertTrue(f.bridge.blocksHtml());
      var first=f.request();until(()->f.mode().equals("STOPPING_HTML"));String firstNonce=f.nonce();
      assertEquals(0,f.transport.count("prepare"));assertTrue(f.client.requests.isEmpty());
      f.acknowledge();ProviderException failure=f.rejection(first);
      assertEquals(NativeSongFailure.rejected(category).code,failure.code);
      String explanation=NativeSongFailure.message(failure,"fallback");assertNotEquals("fallback",explanation);
      assertEquals(explanation,f.bridge.status().get("notice"));assertTrue(explanation.contains("没有加入队列"));
      var response=new MusicController(null,null).unavailable(failure);var body=(Map<?,?>)response.getBody();
      assertEquals(failure.code,body.get("code"));assertEquals(explanation,body.get("error"));
      assertFalse(body.toString().contains("untrusted private remote detail"));
      assertEquals(1,f.client.requests.size());assertEquals("/v2/requests/prepare",f.client.requests.getFirst().uri().getPath());
      assertEquals(0,f.transport.count("commit"));assertTrue(((Map<?,?>)f.bridge.status().get("requests")).containsValue("REJECTED"));assertTrue(f.bridge.blocksHtml());

      f.bridge.source("fresh-source-after-rejection");f.executor.drain();assertEquals("HOLDING_SILENCE",f.mode());
      f.transport.reject=false;var retry=f.request();until(()->f.mode().equals("STOPPING_HTML"));assertNotEquals(firstNonce,f.nonce());
      assertFalse(f.bridge.htmlStopped("owner",f.room.ownerEpoch("owner"),firstNonce,true));assertEquals(1,f.transport.count("prepare"));
      f.acknowledge();var ticket=retry.get(3,TimeUnit.SECONDS);f.bridge.commit(ticket);f.executor.drain();
      assertEquals(2,f.transport.count("prepare"));assertEquals(1,f.transport.count("commit"));assertEquals("NATIVE",f.mode());
      assertEquals(false,f.bridge.status().get("playbackConfirmed"));assertTrue(f.bridge.blocksHtml());

      f.bridge.release();f.executor.drain();assertEquals("HOLDING_SILENCE",f.mode());
      assertEquals("QQ_RELEASE_NOT_CONFIRMED",f.rejection(f.request()).code);
      assertEquals(2,f.transport.count("prepare"));assertEquals(1,f.transport.count("commit"));assertTrue(f.bridge.blocksHtml());
    }
  }
}
