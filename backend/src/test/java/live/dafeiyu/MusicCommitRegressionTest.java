package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import live.dafeiyu.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Latches fix the check/commit interleaving; all media and providers remain local fixtures. */
class MusicCommitRegressionTest {
  @TempDir Path temp;
  final ObjectMapper json=new ObjectMapper();
  static MusicCatalog.Song song(String name){return new MusicCatalog.Song(name,name,"fixture","","/fixture.wav",1000,"local");}
  static void await(CountDownLatch latch){try{assertTrue(latch.await(4,TimeUnit.SECONDS),"gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
  static void blocked(Thread thread)throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
    while(thread.getState()!=Thread.State.BLOCKED){assertTrue(thread.isAlive(),"state mutation escaped the shared commit monitor");assertTrue(System.nanoTime()<end,"expected blocked state mutation");Thread.sleep(1);}
  }
  static void until(BooleanSupplier check)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!check.getAsBoolean()){assertTrue(System.nanoTime()<end,"state timed out");Thread.sleep(2);}}
  static MusicService.Entry current(MusicService music){return (MusicService.Entry)music.status().get("current");}
  static int queued(MusicService music){return ((List<?>)music.status().get("playlist")).size();}
  static AiReplyService ai(){return new AiReplyService(){public String reply(LiveEvent e){throw new AssertionError("second AI request");}public boolean needsRemote(LiveEvent e){return true;}public AiDecision decide(LiveEvent e){return new AiDecision("music_request","fixture","","");}};}
  static LiveEvent comment(String id){return new LiveEvent(id,EventType.COMMENT,"901","fixture","我想听fixture","",0,1000);}
  static final class GateRoom extends LiveRoom {
    final CountDownLatch checked=new CountDownLatch(1),resume=new CountDownLatch(1);volatile boolean gate;
    GateRoom(){super(new MockAiReplyService());}
    @Override public boolean isOwner(String client,String epoch){boolean accepted=super.isOwner(client,epoch);if(gate&&accepted){gate=false;checked.countDown();await(resume);}return accepted;}
  }
  static Stream<Arguments> receiptCases(){return Stream.of("progress","completed","playback").flatMap(op->Stream.of(false,true).map(aba->Arguments.of(op,aba)));}
  @ParameterizedTest @MethodSource("receiptCases")
  void receiptValidationAndMutationLinearizeBeforeReconnect(String operation,boolean aba)throws Exception {
    GateRoom room=new GateRoom();MusicService music=new MusicService(MusicCommitRegressionTest::song,room);
    try{
      room.subscribe("A",true);room.subscribe("B",true);music.request("点歌 one");music.request("点歌 two");
      String id=current(music).entryId(),old=room.ownerEpoch("A");room.gate=true;
      FutureTask<Boolean> receipt=new FutureTask<>(()->switch(operation){case "completed"->music.completed(id,"A",old,false);case "playback"->music.playback(id,"A",old,"PLAYING",12);default->music.progress(id,"A",old,12);});
      Thread receiptThread=Thread.ofPlatform().daemon().start(receipt);await(room.checked);
      FutureTask<Void> reconnect=new FutureTask<>(()->{if(aba){room.release("A");room.subscribe("A",true);room.release("B");}else room.subscribe("A",true);return null;});
      Thread reconnectThread=Thread.ofPlatform().daemon().start(reconnect);
      blocked(reconnectThread);assertFalse(reconnect.isDone());room.resume.countDown();
      assertTrue(receipt.get(4,TimeUnit.SECONDS));reconnect.get(4,TimeUnit.SECONDS);receiptThread.join(1000);
      assertNotEquals(old,room.ownerEpoch("A"));
      assertEquals(operation.equals("completed"),!id.equals(current(music).entryId()));
      Map<?,?> playback=(Map<?,?>)music.status().get("playback");assertEquals(false,playback.get("confirmed"));
      assertNotEquals(room.ownerEpoch("A"),playback.get("ownerEpoch"));
      String freshEntry=current(music).entryId();assertFalse(music.progress(freshEntry,"A",old,30));assertFalse(music.completed(freshEntry,"A",old,false));
    }finally{room.resume.countDown();room.shutdown();}
  }
  @Test void taskCheckAndCommitCannotBeSplitByTimeoutCancellation()throws Exception {
    AtomicLong clock=new AtomicLong(1000);LiveRoom room=new LiveRoom(ai(),clock::get);MusicService music=new MusicService(MusicCommitRegressionTest::song,room);
    CountDownLatch checked=new CountDownLatch(1),resume=new CountDownLatch(1),cancelDone=new CountDownLatch(1);CompletableFuture<String> committed=new CompletableFuture<>();
    room.musicRequests((event,decision,active)->{
      try{var selection=music.searchDecision(decision);String reply=music.semanticEnqueue(selection,()->{boolean accepted=active.getAsBoolean();checked.countDown();await(resume);return accepted;});committed.complete(reply);await(cancelDone);return reply;}catch(Throwable failure){committed.completeExceptionally(failure);throw failure;}
    });
    try{
      room.subscribe("owner",true);room.accept(comment("linearize-cancel"));await(checked);clock.set(31000);
      FutureTask<Void> cancel=new FutureTask<>(()->{room.heartbeat("owner");room.tick();return null;});Thread cancelThread=Thread.ofPlatform().daemon().start(cancel);
      blocked(cancelThread);assertFalse(cancel.isDone());resume.countDown();
      assertTrue(committed.get(4,TimeUnit.SECONDS).contains("已加入"));cancel.get(4,TimeUnit.SECONDS);
      assertTrue(((Map<?,?>)room.status().get("current")).isEmpty());assertEquals(1,queued(music));
    }finally{resume.countDown();cancelDone.countDown();room.shutdown();}
  }
  @Test void cancellationWinsAfterSearchBeforeCommit()throws Exception {
    AtomicLong clock=new AtomicLong(1000);LiveRoom room=new LiveRoom(ai(),clock::get);MusicService music=new MusicService(MusicCommitRegressionTest::song,room);
    CountDownLatch searched=new CountDownLatch(1),commit=new CountDownLatch(1);CompletableFuture<String> outcome=new CompletableFuture<>();
    room.musicRequests((event,decision,active)->{var selected=music.searchDecision(decision);searched.countDown();await(commit);String reply=music.semanticEnqueue(selected,active);outcome.complete(reply);return reply;});
    try{room.subscribe("owner",true);room.accept(comment("cancel-wins"));await(searched);clock.set(31000);room.heartbeat("owner");room.tick();assertTrue(((Map<?,?>)room.status().get("current")).isEmpty());commit.countDown();assertTrue(outcome.get(4,TimeUnit.SECONDS).contains("过期"));assertEquals(0,queued(music));}
    finally{commit.countDown();room.shutdown();}
  }
  @Test void clearingMusicAfterAudioProbeInvalidatesReturnedSelection()throws Exception {
    HttpServer fixture=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);String base="http://127.0.0.1:"+fixture.getAddress().getPort();AtomicInteger probes=new AtomicInteger();
    fixture.createContext("/",e->{try{byte[] body;String path=e.getRequestURI().getPath();if(path.equals("/media")){probes.incrementAndGet();body=MusicIntegrationTest.wav();e.getResponseHeaders().set("Content-Type","audio/wav");}else{body=(path.equals("/v1/search")?"{\"items\":[{\"provider\":\"fixture\",\"id\":\"one\",\"title\":\"old\",\"artist\":\"fixture\"}]}":"{\"url\":\""+base+"/media\"}").getBytes(StandardCharsets.UTF_8);}e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);}finally{e.close();}});fixture.start();
    LiveRoom room=new LiveRoom(new MockAiReplyService());var env=new MockEnvironment().withProperty("music.provider","http").withProperty("music.gateway-url",base).withProperty("music.allow-loopback-fixture","true");MusicService music=new MusicService(new ConfiguredMusicCatalog(env,json,null),room);
    try{var old=music.searchRequest("点歌 old");assertEquals(1,probes.get());music.clear();assertTrue(assertThrows(IllegalArgumentException.class,()->music.enqueueRequest(old)).getMessage().contains("已清空"));assertEquals(0,queued(music));music.request("点歌 old");assertEquals("old",current(music).song().title());assertEquals(2,probes.get());}
    finally{room.shutdown();fixture.stop(0);}
  }
  @Test void sourceGenerationChangeRejectsSameRoomReloginWhileSearching()throws Exception {
    CountDownLatch searching=new CountDownLatch(1),resume=new CountDownLatch(1);LiveRoom room=new LiveRoom(ai());
    MusicCatalog catalog=q->{searching.countDown();await(resume);return song(q);};MusicService music=new MusicService(catalog,room);BilibiliCaptureService capture=new BilibiliCaptureService(json,room,music,"node","fixture-missing");new SemanticMusicRouter(room,music,capture);
    ReflectionTestUtils.setField(capture,"state","live");ReflectionTestUtils.setField(capture,"roomId","123");
    try{
      room.subscribe("owner",true);capture.ingest(json.valueToTree(Map.of("platform","bilibili","id","same-room","roomId","123","type","chat","ts",System.currentTimeMillis(),"user",Map.of("id","902","nickname","fixture"),"data",Map.of("content","我想听fixture"))));await(searching);
      capture.disconnect();ReflectionTestUtils.setField(capture,"state","live");ReflectionTestUtils.setField(capture,"roomId","123");resume.countDown();
      until(()->String.valueOf(((Map<?,?>)room.status().get("current")).get("text")).contains("来源已变化"));assertEquals(0,queued(music));
    }finally{resume.countDown();capture.shutdown();room.shutdown();}
  }
  @Test void slowSearchAndPrepareNeverHoldStateMonitor()throws Exception {
    LiveRoom room=new LiveRoom(new MockAiReplyService());MusicCatalog catalog=mock(MusicCatalog.class);MusicService music=new MusicService(catalog,room);
    CountDownLatch searchEntered=new CountDownLatch(1),searchResume=new CountDownLatch(1),prepareEntered=new CountDownLatch(1),prepareResume=new CountDownLatch(1);
    when(catalog.find("slow")).thenAnswer(c->{assertFalse(Thread.holdsLock(room));assertFalse(Thread.holdsLock(music));searchEntered.countDown();await(searchResume);return song("slow");});when(catalog.find("ready")).thenReturn(song("ready"));
    when(catalog.prepare(any())).thenAnswer(c->{assertFalse(Thread.holdsLock(room));assertFalse(Thread.holdsLock(music));prepareEntered.countDown();await(prepareResume);return c.getArgument(0);});
    try{
      room.subscribe("owner",true);FutureTask<Object> search=new FutureTask<>(()->music.request("点歌 slow"));Thread.ofPlatform().daemon().start(search);await(searchEntered);
      FutureTask<Boolean> responsive=new FutureTask<>(()->room.heartbeat("owner")&&music.status()!=null&&room.status()!=null);Thread.ofPlatform().daemon().start(responsive);assertTrue(responsive.get(1,TimeUnit.SECONDS));
      searchResume.countDown();search.get(4,TimeUnit.SECONDS);music.clear();music.request("点歌 ready");String id=current(music).entryId(),epoch=room.ownerEpoch("owner");
      FutureTask<Object> prepare=new FutureTask<>(()->music.prepare(id,"owner",epoch));Thread.ofPlatform().daemon().start(prepare);await(prepareEntered);
      FutureTask<Boolean> rotate=new FutureTask<>(()->{room.subscribe("owner",true);return room.heartbeat("owner");});Thread.ofPlatform().daemon().start(rotate);assertTrue(rotate.get(1,TimeUnit.SECONDS));prepareResume.countDown();
      assertInstanceOf(ProviderException.class,assertThrows(ExecutionException.class,()->prepare.get(4,TimeUnit.SECONDS)).getCause());
    }finally{searchResume.countDown();prepareResume.countDown();room.shutdown();}
  }
  @Test void disabledBackgroundStillRegistersRequestedLocalMedia()throws Exception {
    Files.write(temp.resolve("fixture.wav"),MusicIntegrationTest.wav());MockEnvironment env=new MockEnvironment().withProperty("music.provider","local").withProperty("music.local-dir",temp.toString()).withProperty("music.local-enabled","false");
    ConfiguredMusicCatalog catalog=new ConfiguredMusicCatalog(env,json,null);LiveRoom room=new LiveRoom(new MockAiReplyService());MusicService music=new MusicService(catalog,room);DefaultMusicPlaylist defaults=new DefaultMusicPlaylist(music,json,env);
    try{defaults.initialize();assertTrue(((Map<?,?>)music.status().get("current")).isEmpty());room.subscribe("owner",true);music.request("点歌 fixture");var entry=current(music);assertTrue(defaults.audio(entry.song().songId().substring(6)).exists());assertEquals(entry.song().audioUrl(),music.prepare(entry.entryId(),"owner",room.ownerEpoch("owner")).get("audioUrl"));}
    finally{room.shutdown();}
  }
  @Test void newLocalFilesCanBeRegisteredWithoutEnablingBackground()throws Exception {
    MockEnvironment env=new MockEnvironment().withProperty("music.local-dir",temp.toString()).withProperty("music.local-enabled","false");ConfiguredMusicCatalog catalog=new ConfiguredMusicCatalog(env,json,null);LiveRoom room=new LiveRoom(new MockAiReplyService());MusicService music=new MusicService(catalog,room);DefaultMusicPlaylist defaults=new DefaultMusicPlaylist(music,json,env);
    try{defaults.initialize();Files.write(temp.resolve("added.wav"),MusicIntegrationTest.wav());var song=catalog.find("added");assertTrue(defaults.audio(song.songId().substring(6)).exists());assertTrue(((Map<?,?>)music.status().get("current")).isEmpty());}
    finally{room.shutdown();}
  }
  @Test void httpProviderPreparesRegisteredLocalBackgroundWithoutGateway()throws Exception {
    Files.write(temp.resolve("background.wav"),MusicIntegrationTest.wav());
    MockEnvironment env=new MockEnvironment().withProperty("music.provider","http").withProperty("music.gateway-url","http://127.0.0.1:1").withProperty("music.local-dir",temp.toString());ConfiguredMusicCatalog catalog=new ConfiguredMusicCatalog(env,json,null);LiveRoom room=new LiveRoom(new MockAiReplyService());MusicService music=new MusicService(catalog,room);DefaultMusicPlaylist defaults=new DefaultMusicPlaylist(music,json,env);
    try{defaults.initialize();room.subscribe("owner",true);var entry=current(music);assertTrue(defaults.audio(entry.song().songId().substring(6)).exists());assertEquals(entry.song().audioUrl(),music.prepare(entry.entryId(),"owner",room.ownerEpoch("owner")).get("audioUrl"));music.requestNotice("fixture");assertEquals("local",current(music).song().source());}
    finally{room.shutdown();}
  }
  @Test void explicitlyDisabledNeteaseNeverCallsLegacyCatalog() {
    NeteaseMusicCatalog legacy=mock(NeteaseMusicCatalog.class);var catalog=new ConfiguredMusicCatalog(new MockEnvironment().withProperty("music.provider","netease"),json,legacy);
    assertTrue(assertThrows(IllegalArgumentException.class,()->catalog.find("requested-song","requested-artist")).getMessage().contains("已停用"));assertEquals(false,catalog.providerStatus().get("configured"));assertEquals(false,catalog.providerStatus().get("controlAvailable"));verifyNoInteractions(legacy);
  }
}
