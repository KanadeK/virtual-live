package live.dafeiyu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static live.dafeiyu.NativeMusicBridgeTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Current Room generations with gated, memory-only transport operations. */
class NativeMusicCommitContractTest {
  static final class Transport implements NativeMusicTransport {
    final LiveRoom room;final List<String> calls=new CopyOnWriteArrayList<>();
    volatile CountDownLatch renewEntered,renewContinue,commitEntered,commitContinue;
    volatile ProviderException commitFailure;volatile boolean pauseProof=true;
    volatile long renewDelayMs,commitDelayMs,releaseDelayMs,observeDelayMs,expiresAt,remainingBudget;
    Transport(LiveRoom room){this.room=room;}
    void outside(){assertFalse(Thread.holdsLock(room));}
    void delay(long ms){try{if(ms>0)Thread.sleep(ms);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ProviderException("QQ_BRIDGE_INTERRUPTED",503);}}
    public void context(long revision,String owner,String source,long expiry,boolean active){outside();calls.add("context:"+active);expiresAt=expiry;if(active){if(renewEntered!=null){renewEntered.countDown();await(renewContinue);}delay(renewDelayMs);}else delay(releaseDelayMs);}
    public Prepared prepare(Request request){outside();return new Prepared(request.requestId(),"reservation","owned",request.query(),request.artist());}
    public void commit(Prepared p){outside();calls.add("commit");if(commitEntered!=null){commitEntered.countDown();await(commitContinue);}delay(commitDelayMs);if(commitFailure!=null)throw commitFailure;}
    public void commit(Prepared p,long deadline){remainingBudget=deadline-System.nanoTime();commit(p);}
    public void cancel(String id){outside();calls.add("cancel");}
    public Observation observe(){outside();calls.add("observe");delay(observeDelayMs);return new Observation(pauseProof,false,false,49,"owned",pauseProof);}
    public void pause(long revision,String owner,String source,boolean paused){outside();calls.add("pause");}
    public void speech(long revision,String owner,String source,String task,boolean active,long expiry){outside();}
    long count(String call){return calls.stream().filter(call::equals).count();}
  }
  static final class Scenario implements AutoCloseable {
    final AtomicLong clock=new AtomicLong(1000);final AtomicBoolean active=new AtomicBoolean(true);
    final LiveRoom room=new LiveRoom(new MockAiReplyService(),clock::get);final ManualExecutor executor=new ManualExecutor();
    final Transport transport=new Transport(room);final NativeMusicBridge bridge;final List<FutureTask<?>> workers=new ArrayList<>();
    Scenario(){room.subscribe("owner",true);bridge=new NativeMusicBridge(room,true,transport,clock::get,executor);executor.drain();}
    String mode(){return String.valueOf(bridge.status().get("mode"));}
    String requestPhase(NativeMusicBridge.Ticket t){return String.valueOf(((Map<?,?>)bridge.status().get("requests")).get(t.request().requestId()));}
    NativeMusicBridge.Ticket prepare()throws Exception {
      var task=new FutureTask<>(()->bridge.prepare("fixture-event",clock.get(),"local-console","fixture","fixture",SONG,active::get,""));workers.add(task);Thread.ofPlatform().daemon().start(task);until(()->mode().equals("STOPPING_HTML"));
      bridge.htmlStopped("owner",room.ownerEpoch("owner"),String.valueOf(bridge.status().get("stopNonce")),true);executor.drain();var ticket=task.get(3,TimeUnit.SECONDS);transport.calls.clear();return ticket;
    }
    FutureTask<Boolean> runOne(){var task=new FutureTask<>(()->{Objects.requireNonNull(executor.queue.poll()).run();return true;});workers.add(task);Thread.ofPlatform().daemon().start(task);return task;}
    void rotate(String kind){switch(kind){case "owner"->room.subscribe("owner",true);case "source"->bridge.source("new-source");case "task"->{synchronized(room){active.set(false);}}default->bridge.release();}}
    public void close(){if(transport.renewContinue!=null)transport.renewContinue.countDown();if(transport.commitContinue!=null)transport.commitContinue.countDown();workers.forEach(t->t.cancel(true));room.shutdown();}
  }
  @Test void renewalUsesFreshNineSecondLeaseAndConsumesTheSameSubmitBudget()throws Exception {
    try(var s=new Scenario()){var ticket=s.prepare();s.clock.set(50000);s.transport.renewDelayMs=180;s.bridge.commit(ticket);s.executor.drain();assertEquals(59000,s.transport.expiresAt);assertEquals(1,s.transport.count("context:true"));assertEquals(1,s.transport.count("commit"));assertTrue(s.transport.remainingBudget<TimeUnit.MILLISECONDS.toNanos(5900));assertTrue(s.transport.remainingBudget>TimeUnit.SECONDS.toNanos(5));assertEquals("QUEUED",s.requestPhase(ticket));}
  }
  @ParameterizedTest @ValueSource(strings={"owner","source","task","clear"})
  void renewalRechecksScopeWithoutHoldingRoomOrSendingAStaleCommit(String kind)throws Exception {
    try(var s=new Scenario()){var ticket=s.prepare();s.transport.renewEntered=new CountDownLatch(1);s.transport.renewContinue=new CountDownLatch(1);s.bridge.commit(ticket);var work=s.runOne();await(s.transport.renewEntered);
      var heartbeat=new FutureTask<>(()->s.room.heartbeat("owner")&&s.bridge.status()!=null);Thread.ofPlatform().daemon().start(heartbeat);assertTrue(heartbeat.get(300,TimeUnit.MILLISECONDS));s.rotate(kind);s.transport.renewContinue.countDown();assertTrue(work.get(1,TimeUnit.SECONDS));s.executor.drain();assertEquals(0,s.transport.count("commit"));assertEquals("INVALIDATED",s.requestPhase(ticket));
    }
  }
  @ParameterizedTest @ValueSource(strings={"owner-success","source-success","clear-success","owner-rejected","source-rejected","clear-rejected"})
  void lateCommitOutcomeCannotOverwriteANewerOwnerSourceOrRelease(String test)throws Exception {
    try(var s=new Scenario()){var ticket=s.prepare();s.transport.commitEntered=new CountDownLatch(1);s.transport.commitContinue=new CountDownLatch(1);if(test.endsWith("rejected"))s.transport.commitFailure=NativeSongFailure.rejected("qq_reconnect_not_ready");s.bridge.commit(ticket);var work=s.runOne();await(s.transport.commitEntered);assertTrue(s.room.heartbeat("owner"));s.rotate(test.substring(0,test.indexOf('-')));s.transport.commitContinue.countDown();assertTrue(work.get(1,TimeUnit.SECONDS));assertEquals("RELEASING",s.mode());assertEquals("INVALIDATED",s.requestPhase(ticket));s.transport.pauseProof=false;s.executor.drain();assertEquals("HOLDING_SILENCE",s.mode());assertTrue(s.bridge.blocksHtml());}
  }
  @Test void definitiveReconnectRejectionIsProjectedButDoesNotProveRelease()throws Exception {
    try(var s=new Scenario()){var ticket=s.prepare();s.transport.commitFailure=NativeSongFailure.rejected("qq_reconnect_not_ready");s.bridge.commit(ticket);s.executor.drain();assertEquals("REJECTED",s.requestPhase(ticket));assertEquals("HOLDING_SILENCE",s.mode());assertTrue(String.valueOf(s.bridge.status().get("notice")).contains("重连"));s.bridge.pump();s.executor.drain();assertEquals("HOLDING_SILENCE",s.mode());assertTrue(s.bridge.blocksHtml());s.transport.pauseProof=false;s.bridge.release();s.executor.drain();assertEquals("HOLDING_SILENCE",s.mode());s.transport.pauseProof=true;s.bridge.pump();s.executor.drain();assertEquals("HTML",s.mode());assertEquals(1,s.transport.count("context:false"));}
  }
  @Test void unknownCommitFailureStaysUncertainInsteadOfFakingRejectionOrPauseProof()throws Exception {
    try(var s=new Scenario()){var ticket=s.prepare();s.transport.commitFailure=new ProviderException("QQ_BRIDGE_UNAVAILABLE",503);s.bridge.commit(ticket);s.executor.drain();assertEquals("COMMITTED",s.requestPhase(ticket));assertEquals("HOLDING_SILENCE",s.mode());assertTrue(String.valueOf(s.bridge.status().get("notice")).contains("未知"));assertTrue(s.bridge.blocksHtml());}
  }
  @Test void closeLetsBoundedSubmitAndQueuedPhysicalCleanupFinish()throws Exception {
    var room=new LiveRoom(new MockAiReplyService());room.subscribe("owner",true);var transport=new Transport(room);var bridge=new NativeMusicBridge(room,true,transport,System::currentTimeMillis,null);
    try{
      until(()->"HTML".equals(bridge.status().get("mode")));var request=new FutureTask<>(()->bridge.prepare("close-fixture",System.currentTimeMillis(),"local-console","fixture","fixture",SONG,()->true,""));Thread.ofPlatform().daemon().start(request);
      until(()->"STOPPING_HTML".equals(bridge.status().get("mode")));bridge.htmlStopped("owner",room.ownerEpoch("owner"),String.valueOf(bridge.status().get("stopNonce")),true);var ticket=request.get(3,TimeUnit.SECONDS);
      transport.commitEntered=new CountDownLatch(1);transport.commitContinue=new CountDownLatch(0);transport.commitDelayMs=5200;transport.releaseDelayMs=2400;transport.observeDelayMs=2400;transport.calls.clear();bridge.commit(ticket);await(transport.commitEntered);long started=System.nanoTime();bridge.close();long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
      assertTrue(elapsed>=9500&&elapsed<12500,"elapsed="+elapsed);assertEquals("HTML",bridge.status().get("mode"));assertEquals(1,transport.count("context:false"));assertEquals(1,transport.count("observe"));
    }finally{bridge.close();room.shutdown();}
  }
}
