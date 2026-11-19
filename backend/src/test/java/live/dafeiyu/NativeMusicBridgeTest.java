package live.dafeiyu;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import live.dafeiyu.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Room/bridge classes with deterministic I/O gates; no QQ/native/paid calls. */
class NativeMusicBridgeTest {
  static final AiDecision SONG=new AiDecision("music_request","小宇","张震岳","");
  static final class ManualExecutor implements Executor {
    final Queue<Runnable> queue=new ConcurrentLinkedQueue<>();
    public void execute(Runnable work){queue.add(work);}
    void drain(){int count=0;for(Runnable work;(work=queue.poll())!=null;){assertTrue(++count<100);work.run();}}
  }
  static final class Fake implements NativeMusicTransport {
    final LiveRoom room;final List<String> calls=new CopyOnWriteArrayList<>();volatile boolean pauseProof=true,failRelease;
    volatile CountDownLatch prepareEntered,prepareResume;volatile Request lastRequest;volatile ProviderException prepareFailure;
    Fake(LiveRoom room){this.room=room;}
    void outside(){assertFalse(Thread.holdsLock(room),"network must not hold Room");}
    public void context(long r,String o,String s,long e,boolean a){outside();calls.add("context:"+a);if(!a&&failRelease)throw new ProviderException("fixture_restore_failed",503);}
    public Prepared prepare(Request request){outside();lastRequest=request;calls.add("prepare");if(prepareFailure!=null)throw prepareFailure;if(prepareEntered!=null){prepareEntered.countDown();await(prepareResume);}return new Prepared(request.requestId(),"reservation","qq-one",request.query(),request.artist());}
    public void commit(Prepared p){outside();calls.add("commit:"+p.requestId());}
    public void cancel(String id){outside();calls.add("cancel:"+id);}
    public Observation observe(){outside();calls.add("observe");return new Observation(pauseProof,false,false,0,"qq-one",pauseProof);}
    public void pause(long r,String o,String s,boolean p){outside();calls.add("pause:"+p);}
    public void speech(long r,String o,String s,String id,boolean a,long e){outside();calls.add("speech:"+a+":"+id);}
    long count(String prefix){return calls.stream().filter(s->s.startsWith(prefix)).count();}
  }
  static final class Fixture implements AutoCloseable {
    final AtomicLong clock=new AtomicLong(1000);final LiveRoom room;final ManualExecutor executor=new ManualExecutor();final Fake fake;final NativeMusicBridge bridge;
    Fixture(){this(new MockAiReplyService());}
    Fixture(AiReplyService ai){room=new LiveRoom(ai,clock::get);room.subscribe("owner",true);fake=new Fake(room);bridge=new NativeMusicBridge(room,true,fake,clock::get,executor);executor.drain();fake.calls.clear();}
    String epoch(){return room.ownerEpoch("owner");}
    String mode(){return String.valueOf(bridge.status().get("mode"));}
    String nonce(){return String.valueOf(bridge.status().get("stopNonce"));}
    NativeMusicBridge.Ticket prepare(BooleanSupplier active)throws Exception {
      var work=new FutureTask<>(()->bridge.prepare(UUID.randomUUID().toString(),clock.get(),"local-console","ds-console","主播本机",SONG,active,""));Thread.ofPlatform().daemon().start(work);
      if(mode().equals("HTML"))until(()->mode().equals("STOPPING_HTML"));
      if(mode().equals("STOPPING_HTML")){assertTrue(bridge.htmlStopped("owner",epoch(),nonce(),true));executor.drain();}
      return work.get(3,TimeUnit.SECONDS);
    }
    void activate()throws Exception {bridge.cancelPrepared(prepare(()->true));executor.drain();fake.calls.clear();}
    public void close(){bridge.close();room.shutdown();}
  }
  static void await(CountDownLatch latch){try{assertTrue(latch.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}
  static void until(BooleanSupplier condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()){assertTrue(System.nanoTime()<end,"condition timed out");Thread.sleep(1);}}
  static String currentTask(LiveRoom room){return String.valueOf(((Map<?,?>)room.status().get("current")).get("taskId"));}

  @Test void nativeAdmissionWaitsForCurrentOwnersActualHtmlStopAck()throws Exception {
    try(var f=new Fixture()){
      var work=new FutureTask<>(()->f.bridge.prepare("one",1000,"local-console","ds-console","主播",SONG,()->true,""));Thread.ofPlatform().daemon().start(work);until(()->f.mode().equals("STOPPING_HTML"));
      assertEquals(0,f.fake.count("context:true"));assertEquals(0,f.fake.count("prepare"));
      assertThrows(ProviderException.class,()->f.bridge.htmlStopped("observer",f.epoch(),f.nonce(),true));
      assertFalse(f.bridge.htmlStopped("owner",f.epoch(),"old-nonce",true));
      assertTrue(f.bridge.htmlStopped("owner",f.epoch(),f.nonce(),true));f.executor.drain();
      assertNotNull(work.get(3,TimeUnit.SECONDS));assertEquals("NATIVE",f.mode());assertEquals(1,f.fake.count("context:true"));assertEquals(0,f.fake.count("commit:"));
    }
  }
  @Test void failedStopNeverEnablesOrSearchesNative()throws Exception {
    try(var f=new Fixture()){
      var work=new FutureTask<>(()->f.bridge.prepare("one",1000,"local-console","ds-console","主播",SONG,()->true,""));Thread.ofPlatform().daemon().start(work);until(()->f.mode().equals("STOPPING_HTML"));
      assertFalse(f.bridge.htmlStopped("owner",f.epoch(),f.nonce(),false));f.executor.drain();assertThrows(ExecutionException.class,()->work.get(1,TimeUnit.SECONDS));assertEquals("HOLDING_SILENCE",f.mode());assertTrue(f.fake.calls.isEmpty());
    }
  }
  @Test void sourceChangeAfterPreparationRejectsLateCommit()throws Exception {
    try(var f=new Fixture()){var ticket=f.prepare(()->true);f.bridge.source("bilibili-new");assertTrue(f.bridge.commit(ticket).contains("过期"));f.executor.drain();assertEquals(0,f.fake.count("commit:"));assertTrue(f.fake.count("cancel:")>0);}
  }
  @Test void ownerReconnectInvalidatesPreparedTicketAndOldStopProof()throws Exception {
    try(var f=new Fixture()){var ticket=f.prepare(()->true);String epoch=f.epoch(),nonce=f.nonce();f.room.subscribe("owner",true);assertNotEquals(epoch,f.epoch());assertThrows(ProviderException.class,()->f.bridge.htmlStopped("owner",epoch,nonce,true));assertTrue(f.bridge.commit(ticket).contains("过期"));f.executor.drain();assertEquals(0,f.fake.count("commit:"));}
  }
  @Test void cancellationWinningBeforeRoomCommitSendsNoNativeCommit()throws Exception {
    try(var f=new Fixture()){AtomicBoolean active=new AtomicBoolean(true);var ticket=f.prepare(active::get);synchronized(f.room){active.set(false);}assertTrue(f.bridge.commit(ticket).contains("过期"));f.executor.drain();assertEquals(0,f.fake.count("commit:"));}
  }
  @Test void finalTaskCheckAndOutboxCommitShareTheRoomMonitor()throws Exception {
    try(var f=new Fixture()){
      AtomicBoolean gate=new AtomicBoolean(),active=new AtomicBoolean(true);CountDownLatch checked=new CountDownLatch(1),resume=new CountDownLatch(1);
      var ticket=f.prepare(()->{boolean value=active.get();if(gate.get()){checked.countDown();await(resume);}return value;});gate.set(true);
      var commit=new FutureTask<>(()->f.bridge.commit(ticket));Thread.ofPlatform().daemon().start(commit);await(checked);
      var cancel=new FutureTask<>(()->{synchronized(f.room){active.set(false);}return true;});Thread cancelThread=Thread.ofPlatform().daemon().start(cancel);until(()->cancelThread.getState()==Thread.State.BLOCKED);assertFalse(cancel.isDone());resume.countDown();
      assertTrue(commit.get(3,TimeUnit.SECONDS).contains("正在提交"));assertTrue(cancel.get(3,TimeUnit.SECONDS));f.executor.drain();assertEquals(0,f.fake.count("commit:"));
    }
  }
  @Test void rotationAfterLocalCommitButBeforeOutboxDrainDropsOldWireCommit()throws Exception {
    try(var f=new Fixture()){var ticket=f.prepare(()->true);f.bridge.commit(ticket);f.room.subscribe("owner",true);f.executor.drain();assertEquals(0,f.fake.count("commit:"));assertTrue(f.fake.count("context:false")>0);}
  }
  @Test void slowNetworkPreparationNeverBlocksRoomHeartbeatOrSnapshots()throws Exception {
    try(var f=new Fixture()){
      f.activate();f.fake.prepareEntered=new CountDownLatch(1);f.fake.prepareResume=new CountDownLatch(1);
      var work=new FutureTask<>(()->f.bridge.prepare("slow",1000,"local-console","ds-console","主播",SONG,()->true,""));Thread.ofPlatform().daemon().start(work);await(f.fake.prepareEntered);
      var heartbeat=new FutureTask<>(()->f.room.heartbeat("owner")&&f.room.status()!=null&&f.bridge.status()!=null);Thread.ofPlatform().daemon().start(heartbeat);assertTrue(heartbeat.get(1,TimeUnit.SECONDS));f.fake.prepareResume.countDown();assertNotNull(work.get(3,TimeUnit.SECONDS));
    }
  }
  @Test void pauseAndResumeKeepHtmlBlockedThroughout()throws Exception {
    try(var f=new Fixture()){f.activate();f.bridge.pause(true);f.executor.drain();assertEquals("NATIVE_PAUSED",f.mode());assertTrue(f.bridge.blocksHtml());f.bridge.pause(false);f.executor.drain();assertEquals("NATIVE",f.mode());assertTrue(f.bridge.blocksHtml());assertEquals(1,f.fake.count("pause:true"));assertEquals(1,f.fake.count("pause:false"));}
  }
  @Test void unknownNativePauseKeepsHtmlSilentUntilFreshReleaseProof()throws Exception {
    try(var f=new Fixture()){f.activate();f.fake.pauseProof=false;f.bridge.release();f.executor.drain();assertEquals("HOLDING_SILENCE",f.mode());assertTrue(f.bridge.blocksHtml());f.fake.pauseProof=true;f.bridge.release();f.executor.drain();assertEquals("HTML",f.mode());assertFalse(f.bridge.blocksHtml());}
  }
  @Test void failedVolumeResetCannotAuthorizeHtmlResume()throws Exception {
    try(var f=new Fixture()){f.activate();f.fake.failRelease=true;f.bridge.release();f.executor.drain();assertEquals("HOLDING_SILENCE",f.mode());assertTrue(f.bridge.blocksHtml());}
  }
  @Test void generatedTextDoesNotDuckOnlyActualCurrentSpeechStartDoes()throws Exception {
    try(var f=new Fixture()){
      f.activate();f.room.manual("真实开始之前不降音");f.executor.drain();assertEquals(0,f.fake.count("speech:true"));String task=currentTask(f.room);
      assertFalse(f.bridge.speech("owner",f.epoch(),"wrong-task","START"));assertTrue(f.bridge.speech("owner",f.epoch(),task,"START"));f.executor.drain();assertEquals(1,f.fake.count("speech:true:"+task));
      f.room.complete(task,"owner");f.executor.drain();assertEquals(1,f.fake.count("speech:false:"+task));assertFalse(f.bridge.speech("owner",f.epoch(),task,"START"));
    }
  }
  @Test void terminalSpeechEventOvertakesQueuedBeginWithoutReducking()throws Exception {
    try(var f=new Fixture()){f.activate();f.room.manual("短语音");String task=currentTask(f.room);assertTrue(f.bridge.speech("owner",f.epoch(),task,"START"));f.room.complete(task,"owner");f.executor.drain();assertEquals(0,f.fake.count("speech:true"));assertEquals(1,f.fake.count("speech:false:"+task));}
  }
  @Test void missingActualSpeechPulseEndsDuckEvenWhileTextTaskStillExists()throws Exception {
    try(var f=new Fixture()){f.activate();f.room.manual("较长语音任务");String task=currentTask(f.room);f.bridge.speech("owner",f.epoch(),task,"START");f.executor.drain();f.clock.addAndGet(5000);f.bridge.pump();f.executor.drain();assertEquals(1,f.fake.count("speech:false:"+task));assertFalse(f.bridge.speech("owner",f.epoch(),task,"PULSE"));}
  }
  @Test void oldHtmlPreparationAndProgressCannotCrossNativeStopBarrier()throws Exception {
    try(var f=new Fixture()){
      MusicCatalog catalog=new MusicCatalog(){public Song find(String q){return new Song("local",q,"local","","/local.wav",1000,"local");}};MusicService music=new MusicService(catalog,f.room);music.request("点歌 local");var entry=(MusicService.Entry)music.status().get("current");String epoch=f.epoch();
      assertNotNull(music.prepare(entry.entryId(),"owner",epoch));
      var work=new FutureTask<>(()->f.bridge.prepare("one",1000,"local-console","ds-console","主播",SONG,()->true,""));Thread.ofPlatform().daemon().start(work);until(()->f.mode().equals("STOPPING_HTML"));
      assertThrows(ProviderException.class,()->music.prepare(entry.entryId(),"owner",epoch));assertFalse(music.progress(entry.entryId(),"owner",epoch,20));assertFalse(music.completed(entry.entryId(),"owner",epoch,false));
      f.bridge.htmlStopped("owner",epoch,f.nonce(),false);assertThrows(ExecutionException.class,()->work.get(1,TimeUnit.SECONDS));
    }
  }
  @Test void defaultLocalModeNeverInvokesNativeTransport(){
    LiveRoom room=new LiveRoom(new MockAiReplyService());Fake fake=new Fake(room);NativeMusicBridge bridge=new NativeMusicBridge(room,false,fake,System::currentTimeMillis,new ManualExecutor());try{room.subscribe("owner",true);bridge.pump();bridge.release();assertFalse(bridge.blocksHtml());assertTrue(fake.calls.isEmpty());assertEquals(false,bridge.status().get("completeSongVerified"));}finally{room.shutdown();}
  }
  @Test void terminalBrowserEndReceivedBeforeStartFencesThatPlayback()throws Exception {
    try(var f=new Fixture()){f.activate();f.room.manual("回执乱序");String task=currentTask(f.room),execution=f.nonce();assertTrue(f.bridge.speech("owner",f.epoch(),execution,task,"playback",2,"END"));assertFalse(f.bridge.speech("owner",f.epoch(),execution,task,"playback",1,"START"));assertFalse(f.bridge.speech("owner",f.epoch(),execution,task,"playback",3,"START"));f.executor.drain();assertEquals(0,f.fake.count("speech:true"));}
  }
  @Test void rejectedPulseBeforeStartDoesNotConsumeItsSequence()throws Exception {
    try(var f=new Fixture()){
      f.activate();f.room.manual("乱序但仍在实际播放");String task=currentTask(f.room),execution=f.nonce();
      assertFalse(f.bridge.speech("owner",f.epoch(),execution,task,"reordered",2,"PULSE"));
      assertTrue(f.bridge.speech("owner",f.epoch(),execution,task,"reordered",1,"START"));f.executor.drain();
      assertTrue(f.bridge.speech("owner",f.epoch(),execution,task,"reordered",3,"PULSE"));assertEquals(1,f.fake.count("speech:true:"+task));
    }
  }
  @Test void retriedStartAfterLostAcknowledgementIsIdempotentAndEndRemainsTerminal()throws Exception {
    try(var f=new Fixture()){
      f.activate();f.room.manual("确认丢失后重试");String task=currentTask(f.room),execution=f.nonce();
      assertTrue(f.bridge.speech("owner",f.epoch(),execution,task,"retry",1,"START"));f.executor.drain();
      assertTrue(f.bridge.speech("owner",f.epoch(),execution,task,"retry",2,"START"));
      assertTrue(f.bridge.speech("owner",f.epoch(),execution,task,"retry",3,"PULSE"));f.executor.drain();assertEquals(1,f.fake.count("speech:true:"+task));
      assertTrue(f.bridge.speech("owner",f.epoch(),execution,task,"retry",4,"END"));
      assertFalse(f.bridge.speech("owner",f.epoch(),execution,task,"retry",5,"START"));f.executor.drain();assertEquals(1,f.fake.count("speech:false:"+task));
    }
  }
  @Test void wrongExecutionAndCancelledTaskCannotBeRevivedByRetriedStart()throws Exception {
    try(var f=new Fixture()){
      f.activate();f.room.manual("待取消语音");String task=currentTask(f.room),execution=f.nonce();
      assertFalse(f.bridge.speech("owner",f.epoch(),"old-execution",task,"retry",1,"START"));
      assertTrue(f.bridge.speech("owner",f.epoch(),execution,task,"retry",2,"START"));f.room.complete(task,"owner");
      assertFalse(f.bridge.speech("owner",f.epoch(),execution,task,"retry",3,"START"));f.executor.drain();assertEquals(0,f.fake.count("speech:true:"+task));
    }
  }
  @Test void ownerRotationRejectsLateStartRetryEvenWithHigherSequence()throws Exception {
    try(var f=new Fixture()){
      f.activate();f.room.manual("旧主播语音");String task=currentTask(f.room),epoch=f.epoch(),execution=f.nonce();
      f.room.subscribe("owner",true);
      assertThrows(ProviderException.class,()->f.bridge.speech("owner",epoch,execution,task,"retry",99,"START"));f.executor.drain();assertEquals(0,f.fake.count("speech:true:"+task));
    }
  }
  @Test void queuedEndIsNotLostWhenNextSpeechStartsBeforeOutboxDrains()throws Exception {
    try(var f=new Fixture()){f.activate();f.room.manual("第一段");String one=currentTask(f.room);f.bridge.speech("owner",f.epoch(),one,"START");f.executor.drain();f.room.manual("第二段");f.room.complete(one,"owner");String two=currentTask(f.room);f.bridge.speech("owner",f.epoch(),two,"START");f.executor.drain();assertTrue(f.fake.calls.indexOf("speech:false:"+one)<f.fake.calls.indexOf("speech:true:"+two));assertEquals(1,f.fake.count("speech:false:"+one));}
  }
  @Test void nativeStartupMustReleasePreviousProcessLeaseBeforeAllowingHtml(){
    LiveRoom room=new LiveRoom(new MockAiReplyService());ManualExecutor executor=new ManualExecutor();Fake fake=new Fake(room);var bridge=new NativeMusicBridge(room,true,fake,System::currentTimeMillis,executor);try{assertTrue(bridge.blocksHtml());executor.drain();assertFalse(bridge.blocksHtml());assertEquals("context:false",fake.calls.getFirst());}finally{room.shutdown();}
  }
  @Test void backendShutdownWaitsForNativeReleaseAndIsIdempotent()throws Exception {
    LiveRoom room=new LiveRoom(new MockAiReplyService());room.subscribe("owner",true);Fake fake=new Fake(room);var bridge=new NativeMusicBridge(room,true,fake,System::currentTimeMillis,null);
    try{until(()->bridge.status().get("mode").equals("HTML"));var prepare=new FutureTask<>(()->bridge.prepare("shutdown",System.currentTimeMillis(),"local-console","ds-console","fixture",SONG,()->true,""));Thread.ofPlatform().daemon().start(prepare);until(()->bridge.status().get("mode").equals("STOPPING_HTML"));bridge.htmlStopped("owner",room.ownerEpoch("owner"),String.valueOf(bridge.status().get("stopNonce")),true);prepare.get(3,TimeUnit.SECONDS);bridge.close();assertEquals("HTML",bridge.status().get("mode"));long releases=fake.count("context:false");assertTrue(releases>=2);bridge.close();assertEquals(releases,fake.count("context:false"));assertEquals("HTML",bridge.status().get("mode"));}finally{bridge.close();room.shutdown();}
  }
}
