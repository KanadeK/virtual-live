package live.dafeiyu;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static live.dafeiyu.NativeMusicBridgeTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real bridge/Room with initially absent media feedback; transport is memory-only. */
class NativeMusicColdStartTest {
  static final class ColdTransport implements NativeMusicTransport {
    final LiveRoom room;
    final List<String> calls=new CopyOnWriteArrayList<>();
    volatile Observation observed=new Observation(false,false,false,0,"",false);
    volatile CountDownLatch observeEntered,observeResume,prepareEntered,prepareResume;
    volatile boolean failCommit;
    ColdTransport(LiveRoom room){this.room=room;}
    void outside(){assertFalse(Thread.holdsLock(room),"transport must run outside Room");}
    public void context(long revision,String owner,String source,long expiry,boolean active){outside();calls.add("context:"+active);}
    public Observation observe(){
      outside();calls.add("observe");Observation result=observed;
      if(observeEntered!=null){observeEntered.countDown();await(observeResume);}return result;
    }
    public Prepared prepare(Request request){
      outside();calls.add("prepare");if(prepareEntered!=null){prepareEntered.countDown();await(prepareResume);}
      return new Prepared(request.requestId(),"reservation","qq-one",request.query(),request.artist());
    }
    public void commit(Prepared prepared){outside();calls.add("commit");if(failCommit)throw new ProviderException("fixture_unknown_commit",503);}
    public void cancel(String requestId){outside();calls.add("cancel");}
    public void pause(long revision,String owner,String source,boolean paused){outside();calls.add("pause:"+paused);}
    public void speech(long revision,String owner,String source,String id,boolean active,long expiry){outside();calls.add("speech:"+active);}
    long count(String call){return calls.stream().filter(call::equals).count();}
  }
  static final class Cold implements AutoCloseable {
    final AtomicLong clock=new AtomicLong(1000);
    final LiveRoom room=new LiveRoom(new MockAiReplyService(),clock::get);
    final ManualExecutor executor=new ManualExecutor();
    final ColdTransport transport=new ColdTransport(room);
    final NativeMusicBridge bridge;
    final List<FutureTask<NativeMusicBridge.Ticket>> workers=new ArrayList<>();
    Cold(boolean drain){room.subscribe("owner",true);bridge=new NativeMusicBridge(room,true,transport,clock::get,executor);if(drain)executor.drain();}
    String mode(){return String.valueOf(bridge.status().get("mode"));}
    String epoch(){return room.ownerEpoch("owner");}
    String nonce(){return String.valueOf(bridge.status().get("stopNonce"));}
    FutureTask<NativeMusicBridge.Ticket> request(BooleanSupplier active){
      var work=new FutureTask<>(()->bridge.prepare(UUID.randomUUID().toString(),clock.get(),"local-console","ds-console","主播",SONG,active,""));
      workers.add(work);Thread.ofPlatform().daemon().start(work);return work;
    }
    void stopAndActivate()throws Exception {
      until(()->mode().equals("STOPPING_HTML"));assertTrue(bridge.blocksHtml());
      assertTrue(bridge.htmlStopped("owner",epoch(),nonce(),true));executor.drain();
    }
    NativeMusicBridge.Ticket prepare()throws Exception {var work=request(()->true);stopAndActivate();return work.get(3,TimeUnit.SECONDS);}
    public void close(){
      if(transport.observeResume!=null)transport.observeResume.countDown();
      if(transport.prepareResume!=null)transport.prepareResume.countDown();
      workers.forEach(work->work.cancel(true));room.shutdown();
    }
  }
  static ProviderException rejected(FutureTask<?> work)throws Exception {
    ExecutionException error=assertThrows(ExecutionException.class,()->work.get(3,TimeUnit.SECONDS));
    return assertInstanceOf(ProviderException.class,error.getCause());
  }

  @Test void coldRequestWaitsForCurrentOwnerAndFreshStopAckBeforeNormalPrepareCommit()throws Exception {
    try(var f=new Cold(true)){
      assertEquals("HOLDING_SILENCE",f.mode());assertTrue(f.bridge.blocksHtml());
      assertEquals("",((Map<?,?>)f.bridge.status().get("current")).get("id"));
      var work=f.request(()->true);until(()->f.mode().equals("STOPPING_HTML"));
      assertFalse(f.nonce().isBlank());assertFalse(work.isDone());assertEquals(0,f.transport.count("context:true"));assertEquals(0,f.transport.count("prepare"));
      assertThrows(ProviderException.class,()->f.bridge.htmlStopped("observer",f.epoch(),f.nonce(),true));
      assertThrows(ProviderException.class,()->f.bridge.htmlStopped("owner","old-epoch",f.nonce(),true));
      assertFalse(f.bridge.htmlStopped("owner",f.epoch(),"old-nonce",true));
      f.stopAndActivate();var ticket=work.get(3,TimeUnit.SECONDS);
      assertEquals(1,f.transport.count("prepare"));assertEquals(0,f.transport.count("commit"));
      f.bridge.commit(ticket);f.executor.drain();assertEquals(1,f.transport.count("commit"));
      assertEquals(List.of("context:false","observe","context:true","prepare","context:true","commit"),f.transport.calls);
      assertEquals("NATIVE",f.mode());assertTrue(f.bridge.blocksHtml());assertEquals(false,f.bridge.status().get("playbackConfirmed"));
    }
  }
  @Test void failedColdStopDoesNotPrepareAndRetryRequiresAnotherStopNonce()throws Exception {
    try(var f=new Cold(true)){
      var first=f.request(()->true);until(()->f.mode().equals("STOPPING_HTML"));String oldNonce=f.nonce();
      assertFalse(f.bridge.htmlStopped("owner",f.epoch(),oldNonce,false));rejected(first);
      assertEquals("HOLDING_SILENCE",f.mode());assertEquals(0,f.transport.count("context:true"));assertEquals(0,f.transport.count("prepare"));assertTrue(f.bridge.blocksHtml());
      var retry=f.request(()->true);until(()->f.mode().equals("STOPPING_HTML"));assertNotEquals(oldNonce,f.nonce());
      assertFalse(f.bridge.htmlStopped("owner",f.epoch(),oldNonce,true));f.stopAndActivate();assertNotNull(retry.get(3,TimeUnit.SECONDS));
    }
  }
  @Test void lateInitialReleaseProofCannotReopenHtmlOrReplaceNewStopBarrier()throws Exception {
    try(var f=new Cold(false)){
      f.transport.observed=new NativeMusicTransport.Observation(true,false,false,0,"",true);
      f.transport.observeEntered=new CountDownLatch(1);f.transport.observeResume=new CountDownLatch(1);
      var release=new FutureTask<>(()->{f.executor.drain();return true;});Thread.ofPlatform().daemon().start(release);await(f.transport.observeEntered);
      var request=f.request(()->true);until(()->f.mode().equals("STOPPING_HTML"));String nonce=f.nonce();
      f.transport.observeResume.countDown();assertTrue(release.get(3,TimeUnit.SECONDS));
      assertEquals("STOPPING_HTML",f.mode());assertEquals(nonce,f.nonce());assertTrue(f.bridge.blocksHtml());assertEquals(0,f.transport.count("prepare"));
      f.stopAndActivate();assertNotNull(request.get(3,TimeUnit.SECONDS));assertTrue(f.bridge.blocksHtml());
    }
  }
  @Test void startupUnknownPreviousTrackNeverAuthorizesHtmlRelease(){
    try(var f=new Cold(false)){
      f.transport.observed=new NativeMusicTransport.Observation(false,false,false,12,"prior-process-track",false);
      f.executor.drain();assertEquals("HOLDING_SILENCE",f.mode());assertTrue(f.bridge.blocksHtml());
      f.bridge.release();f.executor.drain();assertEquals("HOLDING_SILENCE",f.mode());assertTrue(f.bridge.blocksHtml());
      assertEquals(0,f.transport.count("context:true"));assertEquals(0,f.transport.count("prepare"));
    }
  }
  @Test void afterWireCommitAttemptUnknownReleaseStillRejectsAnotherRequest()throws Exception {
    try(var f=new Cold(true)){
      var ticket=f.prepare();f.bridge.commit(ticket);f.executor.drain();assertEquals(1,f.transport.count("commit"));
      f.bridge.release();f.executor.drain();assertEquals("HOLDING_SILENCE",f.mode());
      String nonce=f.nonce();long prepares=f.transport.count("prepare");
      assertEquals("QQ_RELEASE_NOT_CONFIRMED",rejected(f.request(()->true)).code);
      assertEquals(nonce,f.nonce());assertEquals(prepares,f.transport.count("prepare"));assertTrue(f.bridge.blocksHtml());
      f.transport.observed=new NativeMusicTransport.Observation(true,false,false,0,"qq-one",true);
      f.bridge.release();f.executor.drain();assertEquals("HTML",f.mode());assertFalse(f.bridge.blocksHtml());
    }
  }
  @Test void unknownWireCommitResultAlsoClosesStartupRecovery()throws Exception {
    try(var f=new Cold(true)){
      var ticket=f.prepare();f.transport.failCommit=true;f.bridge.commit(ticket);f.executor.drain();
      assertEquals("HOLDING_SILENCE",f.mode());assertEquals(1,f.transport.count("commit"));
      assertEquals("QQ_RELEASE_NOT_CONFIRMED",rejected(f.request(()->true)).code);assertTrue(f.bridge.blocksHtml());
    }
  }
  @Test void cancelledBeforeColdRequestCannotChangeBarrierOrCallTransport()throws Exception {
    try(var f=new Cold(true)){
      long revision=((Number)f.bridge.status().get("revision")).longValue();
      assertEquals("QQ_REQUEST_EXPIRED",rejected(f.request(()->false)).code);
      assertEquals("HOLDING_SILENCE",f.mode());assertEquals(revision,((Number)f.bridge.status().get("revision")).longValue());
      assertEquals(List.of("context:false","observe"),f.transport.calls);
    }
  }
  @Test void cancellationAfterStopAckPreventsPrepareAndCommit()throws Exception {
    try(var f=new Cold(true)){
      var active=new AtomicBoolean(true);var work=f.request(active::get);until(()->f.mode().equals("STOPPING_HTML"));
      assertTrue(f.bridge.htmlStopped("owner",f.epoch(),f.nonce(),true));active.set(false);f.executor.drain();
      assertEquals("QQ_REQUEST_EXPIRED",rejected(work).code);assertEquals(0,f.transport.count("prepare"));assertEquals(0,f.transport.count("commit"));assertTrue(f.bridge.blocksHtml());
    }
  }
  @Test void cancellationDuringFirstPrepareInvalidatesReservationAndCannotCommit()throws Exception {
    try(var f=new Cold(true)){
      f.transport.prepareEntered=new CountDownLatch(1);f.transport.prepareResume=new CountDownLatch(1);
      var active=new AtomicBoolean(true);var work=f.request(active::get);f.stopAndActivate();await(f.transport.prepareEntered);
      active.set(false);f.transport.prepareResume.countDown();assertEquals("QQ_REQUEST_EXPIRED",rejected(work).code);f.executor.drain();
      assertEquals(1,f.transport.count("prepare"));assertEquals(1,f.transport.count("cancel"));assertEquals(0,f.transport.count("commit"));assertTrue(f.bridge.blocksHtml());
    }
  }
  @Test void sourceAndOwnerRotationFenceColdStopAcksWithoutLosingFirstRequestRecovery()throws Exception {
    try(var f=new Cold(true)){
      var first=f.request(()->true);until(()->f.mode().equals("STOPPING_HTML"));String sourceNonce=f.nonce();
      f.bridge.source("bilibili-new");rejected(first);f.executor.drain();assertEquals("HOLDING_SILENCE",f.mode());
      var second=f.request(()->true);until(()->f.mode().equals("STOPPING_HTML"));assertNotEquals(sourceNonce,f.nonce());
      assertFalse(f.bridge.htmlStopped("owner",f.epoch(),sourceNonce,true));String oldEpoch=f.epoch(),ownerNonce=f.nonce();
      f.room.subscribe("owner",true);rejected(second);f.executor.drain();
      assertThrows(ProviderException.class,()->f.bridge.htmlStopped("owner",oldEpoch,ownerNonce,true));
      var third=f.request(()->true);f.stopAndActivate();assertNotNull(third.get(3,TimeUnit.SECONDS));assertEquals(1,f.transport.count("prepare"));assertEquals(0,f.transport.count("commit"));
    }
  }
  @Test void invalidatedLocalCommitWithoutWireAttemptDoesNotSealStartupRecovery()throws Exception {
    try(var f=new Cold(true)){
      var ticket=f.prepare();f.bridge.commit(ticket);f.bridge.source("rotated-before-outbox");f.executor.drain();
      assertEquals("HOLDING_SILENCE",f.mode());assertEquals(0,f.transport.count("commit"));assertEquals(1,f.transport.count("cancel"));
      var next=f.request(()->true);f.stopAndActivate();assertNotNull(next.get(3,TimeUnit.SECONDS));assertEquals(2,f.transport.count("prepare"));assertTrue(f.bridge.blocksHtml());
    }
  }
}
