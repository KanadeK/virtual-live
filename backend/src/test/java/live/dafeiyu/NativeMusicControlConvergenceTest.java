package live.dafeiyu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static live.dafeiyu.NativeMusicBridgeTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Delayed real-proof shape, with actual Room/bridge and memory-only gated transport. */
class NativeMusicControlConvergenceTest {
  static final class Transport implements NativeMusicTransport {
    final LiveRoom room;final List<String> calls=new CopyOnWriteArrayList<>();
    volatile Observation observed=new Observation(true,false,false,0,"owned",true);
    volatile boolean failRelease,failObserve,failPause;
    volatile CountDownLatch observeEntered,observeResume,pauseEntered,pauseResume;
    Transport(LiveRoom room){this.room=room;}
    void outside(){assertFalse(Thread.holdsLock(room));}
    public void context(long r,String o,String s,long e,boolean active){outside();calls.add("context:"+active);if(!active&&failRelease)throw new ProviderException("fixture_restore_failed",503);}
    public Observation observe(){
      outside();calls.add("observe");var value=observed;boolean failure=failObserve;
      if(observeEntered!=null){observeEntered.countDown();await(observeResume);}
      if(failure)throw new ProviderException("fixture_observe_failed",503);return value;
    }
    public Prepared prepare(Request r){outside();calls.add("prepare");return new Prepared(r.requestId(),"reservation","owned",r.query(),r.artist());}
    public void commit(Prepared p){outside();calls.add("commit");}
    public void cancel(String id){outside();calls.add("cancel");}
    public void pause(long r,String o,String s,boolean paused){outside();calls.add("pause:"+paused);if(paused&&pauseEntered!=null){pauseEntered.countDown();await(pauseResume);}if(paused&&failPause)throw new ProviderException("fixture_pause_failed",503);}
    public void speech(long r,String o,String s,String t,boolean a,long e){outside();calls.add("speech:"+a);}
    long count(String value){return calls.stream().filter(value::equals).count();}
  }
  static final class Scenario implements AutoCloseable {
    final AtomicLong clock=new AtomicLong(1000);final LiveRoom room=new LiveRoom(new MockAiReplyService(),clock::get);
    final ManualExecutor executor=new ManualExecutor();final Transport transport=new Transport(room);final NativeMusicBridge bridge;
    final List<FutureTask<?>> workers=new ArrayList<>();
    Scenario() {room.subscribe("owner",true);bridge=new NativeMusicBridge(room,true,transport,clock::get,executor);executor.drain();}
    String mode(){return String.valueOf(bridge.status().get("mode"));}
    String notice(){return String.valueOf(bridge.status().get("notice"));}
    FutureTask<NativeMusicBridge.Ticket> request(){var work=new FutureTask<>(()->bridge.prepare(UUID.randomUUID().toString(),clock.get(),"local-console","ds-console","fixture",SONG,()->true,""));workers.add(work);Thread.ofPlatform().daemon().start(work);return work;}
    void activate()throws Exception {
      var work=request();until(()->mode().equals("STOPPING_HTML"));assertTrue(bridge.htmlStopped("owner",room.ownerEpoch("owner"),String.valueOf(bridge.status().get("stopNonce")),true));executor.drain();bridge.commit(work.get(3,TimeUnit.SECONDS));executor.drain();transport.calls.clear();
    }
    void proof(boolean paused){transport.observed=new NativeMusicTransport.Observation(paused,false,false,34,"owned",paused);}
    void tick(){bridge.pump();executor.drain();}
    FutureTask<Boolean> runOne(){var work=new FutureTask<>(()->{Objects.requireNonNull(executor.queue.poll()).run();return true;});workers.add(work);Thread.ofPlatform().daemon().start(work);return work;}
    void observeGate(){transport.observeEntered=new CountDownLatch(1);transport.observeResume=new CountDownLatch(1);}
    void rotate(String kind){if(kind.equals("owner"))room.subscribe("owner",true);else if(kind.equals("source"))bridge.source("new-source");else bridge.release();}
    public void close(){if(transport.observeResume!=null)transport.observeResume.countDown();if(transport.pauseResume!=null)transport.pauseResume.countDown();workers.forEach(work->work.cancel(true));room.shutdown();}
  }

  @Test void delayedPauseProofConvergesOnPumpWithoutAnotherPauseCommand()throws Exception {
    try(var s=new Scenario()){s.activate();s.proof(false);s.bridge.pause(true);s.executor.drain();assertEquals("NATIVE",s.mode());assertTrue(s.bridge.blocksHtml());s.proof(true);s.tick();assertEquals("NATIVE_PAUSED",s.mode());assertEquals(1,s.transport.count("pause:true"));assertTrue(s.bridge.blocksHtml());}
  }
  @Test void delayedReleaseProofConvergesOnPumpWithoutAnotherClearCommand()throws Exception {
    try(var s=new Scenario()){s.activate();s.proof(false);s.bridge.release();s.executor.drain();assertEquals("HOLDING_SILENCE",s.mode());assertTrue(s.bridge.blocksHtml());s.proof(true);s.tick();assertEquals("HTML",s.mode());assertFalse(s.bridge.blocksHtml());assertEquals(1,s.transport.count("context:false"));assertEquals(0,s.transport.count("context:true"));assertEquals("",s.bridge.status().get("stopNonce"));}
  }
  @Test void elapsedTimeAndRepeatedUnconfirmedReadsNeverBecomeProof()throws Exception {
    try(var s=new Scenario()){s.activate();s.proof(false);s.bridge.pause(true);s.executor.drain();for(int i=0;i<3;i++){s.clock.addAndGet(3600000);s.tick();assertEquals("NATIVE",s.mode());assertTrue(s.bridge.blocksHtml());}s.bridge.release();s.executor.drain();s.transport.calls.clear();for(int i=0;i<3;i++){s.clock.addAndGet(3600000);s.tick();assertEquals("HOLDING_SILENCE",s.mode());assertTrue(s.bridge.blocksHtml());}assertEquals(3,s.transport.count("observe"));assertEquals(0,s.transport.count("context:true"));assertEquals(0,s.transport.count("context:false"));assertEquals(0,s.transport.count("pause:true"));}
  }
  @Test void failedVolumeResetCannotBeSubstitutedByLaterPauseObservation()throws Exception {
    try(var s=new Scenario()){s.activate();s.transport.failRelease=true;s.proof(true);s.bridge.release();s.executor.drain();s.transport.calls.clear();s.tick();assertEquals("HOLDING_SILENCE",s.mode());assertTrue(s.bridge.blocksHtml());assertEquals(0,s.transport.count("observe"));s.transport.failRelease=false;s.bridge.release();s.executor.drain();assertEquals("HTML",s.mode());}
  }
  @Test void successfulInactiveAckWithFailedFirstObservationMayRetryReadOnly()throws Exception {
    try(var s=new Scenario()){s.activate();s.transport.failObserve=true;s.bridge.release();s.executor.drain();assertEquals("HOLDING_SILENCE",s.mode());s.transport.failObserve=false;s.proof(true);s.tick();assertEquals("HTML",s.mode());assertEquals(1,s.transport.count("context:false"));assertEquals(0,s.transport.count("context:true"));}
  }
  @Test void latePauseProofCannotOverrideNewerResume()throws Exception {
    try(var s=new Scenario()){s.activate();s.proof(false);s.bridge.pause(true);s.executor.drain();s.proof(true);s.observeGate();s.bridge.pump();var old=s.runOne();await(s.transport.observeEntered);s.bridge.pause(false);String notice=s.notice();s.transport.observeResume.countDown();assertTrue(old.get(3,TimeUnit.SECONDS));assertEquals("NATIVE",s.mode());assertEquals(notice,s.notice());s.executor.drain();assertEquals("NATIVE",s.mode());assertEquals(1,s.transport.count("pause:false"));}
  }
  @ParameterizedTest @ValueSource(strings={"owner","source"})
  void latePauseProofCannotOverrideNewOwnerOrSource(String kind)throws Exception {
    try(var s=new Scenario()){s.activate();s.proof(false);s.bridge.pause(true);s.executor.drain();s.proof(true);s.observeGate();s.bridge.pump();var old=s.runOne();await(s.transport.observeEntered);s.rotate(kind);s.transport.observeResume.countDown();assertTrue(old.get(3,TimeUnit.SECONDS));assertEquals("RELEASING",s.mode());assertTrue(s.bridge.blocksHtml());s.proof(false);s.executor.drain();assertEquals("HOLDING_SILENCE",s.mode());}
  }
  @ParameterizedTest @ValueSource(strings={"owner","source","cancel-release"})
  void lateReleaseProofCannotOverrideNewGeneration(String kind)throws Exception {
    try(var s=new Scenario()){s.activate();s.proof(false);s.bridge.release();s.executor.drain();s.proof(true);s.observeGate();s.bridge.pump();var old=s.runOne();await(s.transport.observeEntered);s.rotate(kind);s.transport.observeResume.countDown();assertTrue(old.get(3,TimeUnit.SECONDS));assertEquals("RELEASING",s.mode());assertTrue(s.bridge.blocksHtml());s.proof(false);s.executor.drain();assertEquals("HOLDING_SILENCE",s.mode());}
  }
  @Test void failedHtmlStopIsNotAReleasePendingConfirmation()throws Exception {
    try(var s=new Scenario()){var work=s.request();until(()->s.mode().equals("STOPPING_HTML"));assertFalse(s.bridge.htmlStopped("owner",s.room.ownerEpoch("owner"),String.valueOf(s.bridge.status().get("stopNonce")),false));assertThrows(ExecutionException.class,()->work.get(3,TimeUnit.SECONDS));s.transport.calls.clear();s.proof(true);s.tick();assertEquals("HOLDING_SILENCE",s.mode());assertTrue(s.bridge.blocksHtml());assertTrue(s.transport.calls.isEmpty());}
  }
  @Test void latePauseFailureCannotOverrideNewerResumeNotice()throws Exception {
    try(var s=new Scenario()){s.activate();s.transport.pauseEntered=new CountDownLatch(1);s.transport.pauseResume=new CountDownLatch(1);s.transport.failPause=true;s.bridge.pause(true);var old=s.runOne();await(s.transport.pauseEntered);s.bridge.pause(false);String notice=s.notice();s.transport.pauseResume.countDown();assertTrue(old.get(3,TimeUnit.SECONDS));assertEquals("NATIVE",s.mode());assertEquals(notice,s.notice());s.executor.drain();assertEquals("NATIVE",s.mode());}
  }
  @Test void lateNativePollFailureCannotOverrideNewerResumeNotice()throws Exception {
    try(var s=new Scenario()){s.activate();s.observeGate();s.transport.failObserve=true;s.bridge.pump();var old=s.runOne();await(s.transport.observeEntered);s.bridge.pause(false);String notice=s.notice();s.transport.observeResume.countDown();assertTrue(old.get(3,TimeUnit.SECONDS));assertEquals("NATIVE",s.mode());assertEquals(notice,s.notice());s.transport.failObserve=false;s.executor.drain();assertEquals("NATIVE",s.mode());}
  }
}
