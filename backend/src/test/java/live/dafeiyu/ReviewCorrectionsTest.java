package live.dafeiyu;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static live.dafeiyu.NativeMusicBridgeTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only the newly reported stale-failure boundaries; native transport is entirely in memory. */
class ReviewCorrectionsTest {
  static final class Gate implements NativeMusicTransport {
    final LiveRoom room;final CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);final AtomicInteger prepares=new AtomicInteger(),cancels=new AtomicInteger();
    volatile Request old;
    Gate(LiveRoom room){this.room=room;}
    public void context(long r,String o,String s,long e,boolean a){}
    public Observation observe(){return new Observation(true,false,false,0,"",true);}
    public Prepared prepare(Request r){if(prepares.incrementAndGet()==1){old=r;entered.countDown();await(resume);throw new ProviderException("QQ_SONG_AMBIGUOUS",409);}return new Prepared(r.requestId(),"reservation","new-song","新曲","歌手");}
    public void commit(Prepared p){}
    public void cancel(String id){assertFalse(Thread.holdsLock(room),"remote cancel stays outside Room");cancels.incrementAndGet();}
    public void pause(long r,String o,String s,boolean p){}
    public void speech(long r,String o,String s,String t,boolean a,long e){}
  }
  static final class Scenario implements AutoCloseable {
    final LiveRoom room=new LiveRoom(new MockAiReplyService(),()->1000L);final ManualExecutor executor=new ManualExecutor();final Gate gate=new Gate(room);final NativeMusicBridge bridge;
    Scenario(){room.subscribe("owner",true);bridge=new NativeMusicBridge(room,true,gate,()->1000L,executor);executor.drain();}
    FutureTask<NativeMusicBridge.Ticket> request(java.util.function.BooleanSupplier active)throws Exception {
      var future=new FutureTask<>(()->bridge.prepare(UUID.randomUUID().toString(),1000,"local-console","ds-console","fixture",SONG,active,""));Thread.ofPlatform().daemon().start(future);
      until(()->bridge.status().get("mode").equals("STOPPING_HTML"));assertTrue(bridge.htmlStopped("owner",room.ownerEpoch("owner"),String.valueOf(bridge.status().get("stopNonce")),true));executor.drain();return future;
    }
    String notice(){return String.valueOf(bridge.status().get("notice"));}
    String phase(){return String.valueOf(((Map<?,?>)bridge.status().get("requests")).get(gate.old.requestId()));}
    ProviderException failure(FutureTask<?> future)throws Exception {var failure=assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));return assertInstanceOf(ProviderException.class,failure.getCause());}
    public void close(){gate.resume.countDown();room.shutdown();}
  }
  @Test void oldOwnerFailureCannotOverwriteNewOwnerCommitSuccess()throws Exception {
    try(var s=new Scenario()){
      var old=s.request(()->true);await(s.gate.entered);s.room.subscribe("owner",true);s.executor.drain();
      var current=s.request(()->true).get(3,TimeUnit.SECONDS);s.bridge.commit(current);s.executor.drain();String before=s.notice();assertTrue(before.contains("已进入QQ正常队列"));
      s.gate.resume.countDown();assertEquals("QQ_REQUEST_EXPIRED",s.failure(old).code);assertEquals(before,s.notice());assertEquals("INVALIDATED",s.phase());assertEquals("NATIVE",s.bridge.status().get("mode"));assertEquals(1,s.gate.cancels.get());
    }
  }
  @Test void cancelledTaskFailureIsInvalidatedWithoutChangingCurrentNotice()throws Exception {
    try(var s=new Scenario()){
      var active=new AtomicBoolean(true);var old=s.request(active::get);await(s.gate.entered);String before=s.notice();active.set(false);s.gate.resume.countDown();
      assertEquals("QQ_REQUEST_EXPIRED",s.failure(old).code);assertEquals(before,s.notice());assertEquals("INVALIDATED",s.phase());assertEquals(1,s.gate.cancels.get());
    }
  }
  @Test void currentActiveFailureStillShowsItsClassifiedRejection()throws Exception {
    try(var s=new Scenario()){
      var current=s.request(()->true);await(s.gate.entered);s.gate.resume.countDown();assertEquals("QQ_SONG_AMBIGUOUS",s.failure(current).code);
      assertEquals("REJECTED",s.phase());assertTrue(s.notice().contains("多个同名QQ曲目"));assertEquals(1,s.gate.cancels.get());
    }
  }
}
