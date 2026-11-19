package live.dafeiyu;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import live.dafeiyu.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** All providers here are localhost fixtures. No environment secrets or real music accounts are used. */
class MusicIntegrationTest {
  @TempDir Path temp;
  final ObjectMapper json=new ObjectMapper();HttpServer fixture;String base;AtomicInteger requests=new AtomicInteger();
  @BeforeEach void start()throws Exception {fixture=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);fixture.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));fixture.start();base="http://127.0.0.1:"+fixture.getAddress().getPort();}
  @AfterEach void stop(){fixture.stop(0);}
  void answer(String path,String mime,Supplier<String> body){fixture.createContext(path,e->{try{requests.incrementAndGet();byte[] data=body.get().getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type",mime);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);}finally{e.close();}});}
  MockEnvironment env(){return new MockEnvironment().withProperty("integration.data-dir",temp.resolve("usage").toString()).withProperty("integration.ai-provider","deepseek").withProperty("integration.deepseek-key","fixture-not-real").withProperty("integration.deepseek-base-url",base).withProperty("integration.ai-daily-limit","20").withProperty("integration.tts-daily-limit","0");}
  String decision(String intent,String query,String reply)throws Exception{return json.writeValueAsString(Map.of("v",1,"intent",intent,"query",query,"artist","","reply",reply));}
  LiveEvent comment(String id,String user,String content){return new LiveEvent(id,EventType.COMMENT,user,"夹具观众",content,"",0,System.currentTimeMillis());}
  void until(BooleanSupplier assertion)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!assertion.getAsBoolean()){if(System.nanoTime()>end)fail("Local fixture timed out");Thread.sleep(5);}}
  @SuppressWarnings("unchecked") List<MusicService.Entry> songs(MusicService music){return (List<MusicService.Entry>)music.status().get("playlist");}
  static byte[] wav(){byte[] bytes=new byte[48044];ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(bytes.length-8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(48000);return bytes;}
  ConfiguredMusicCatalog catalog(MockEnvironment e){return new ConfiguredMusicCatalog(e,json,mock(NeteaseMusicCatalog.class));}

  @Test void structuredDecisionUsesExactlyOneRequestAndVersionedCache()throws Exception {
    AtomicReference<JsonNode> sent=new AtomicReference<>();
    String music=decision("music_request","小宇","");
    fixture.createContext("/chat/completions",e->{try{requests.incrementAndGet();sent.set(json.readTree(e.getRequestBody()));byte[] data=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",music))),"usage",Map.of("prompt_tokens",31,"completion_tokens",20)));e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);}finally{e.close();}});
    IntegrationConfig config=new IntegrationConfig(env());UsageLedger ledger=new UsageLedger(config,json);
    DeepSeekAiReplyService ai=new DeepSeekAiReplyService(config,json,ledger,new ProviderStore(config));
    assertEquals("小宇",ai.decide(comment("a","u","我想听小宇")).query());
    assertTrue(ai.decide(comment("b","v","我想听小宇")).requestsMusic());assertEquals(1,requests.get());
    assertEquals("json_object",sent.get().path("response_format").path("type").asText());assertEquals("disabled",sent.get().path("thinking").path("type").asText());
    assertEquals(51L,ledger.status().get("aiTotalTokens"));
  }
  @Test void malformedEmptyExtraFieldsAndTruncatedJsonCannotBecomeMusicActions()throws Exception {
    String valid=decision("music_request","小宇","");
    for(String bad:List.of("",valid.substring(0,valid.length()-1),valid.replace("\"v\":1","\"v\":\"1\""),valid.replace("\"reply\":\"\"","\"reply\":\"已经播放\""),valid.substring(0,valid.length()-1)+",\"url\":\"https://invalid\"}")){
      assertThrows(ProviderException.class,()->AiDecision.parse(json,bad));
    }
    assertFalse(AiDecision.parse(json,decision("chat","","刚才那首很好听。")).requestsMusic());
    assertThrows(ProviderException.class,()->AiDecision.parse(json,decision("music_request","https://example.invalid","")));
  }
  @Test void invalidDecisionFallsBackWithoutASecondPaidAttempt()throws Exception {
    answer("/chat/completions","application/json",()->"{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"v\\\":1\"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3}}");
    IntegrationConfig config=new IntegrationConfig(env());var service=new ConfiguredAiReplyService(config,json,new UsageLedger(config,json),new ProviderStore(config),new MockAiReplyService());
    assertFalse(service.decide(comment("a","u","我想听小宇")).requestsMusic());assertEquals(1,requests.get());assertEquals("DEEPSEEK_INCOMPLETE_DECISION",service.status().get("lastFallback"));
  }
  @Test void naturalRequestRoutesOnceAndOrdinaryConversationNeverSearches()throws Exception {
    AtomicInteger decisions=new AtomicInteger();
    AiReplyService ai=new AiReplyService(){public String reply(LiveEvent e){fail("Second text generation is not allowed");return "";}public boolean needsRemote(LiveEvent e){return true;}public AiDecision decide(LiveEvent e){decisions.incrementAndGet();return e.content().equals("我想听小宇")?new AiDecision("music_request","小宇","",""):AiDecision.chat("收到，我们聊聊。");}};
    LiveRoom room=new LiveRoom(ai);MusicCatalog source=mock(MusicCatalog.class);when(source.find(anyString())).thenAnswer(c->new MusicCatalog.Song("qq:1",c.getArgument(0),"歌手","","/fixture.wav",1000,"tencent"));
    MusicService music=new MusicService(source,room);BilibiliCaptureService capture=new BilibiliCaptureService(json,room,music,"node","missing-fixture-worker");new SemanticMusicRouter(room,music,capture);
    try {room.subscribe("owner",true);room.accept(comment("natural","u","我想听小宇"));until(()->songs(music).size()==1&&!((Map<?,?>)room.status().get("current")).get("text").toString().isBlank());
      assertEquals(1,decisions.get());verify(source,times(1)).find("小宇");room.accept(comment("natural","u","我想听小宇"));assertEquals(1,decisions.get());
      String task=((Map<?,?>)room.status().get("current")).get("taskId").toString();assertTrue(room.complete(task,"owner"));
      room.accept(comment("mention","other","我想听你讲讲小宇这首歌"));until(()->decisions.get()==2);verify(source,times(1)).find(anyString());
    }finally{capture.shutdown();room.shutdown();}
  }
  @Test void directNegationCannotEnqueueEvenIfModelReturnsWrongIntent()throws Exception {
    AiReplyService ai=new AiReplyService(){public String reply(LiveEvent e){return "";}public boolean needsRemote(LiveEvent e){return true;}public AiDecision decide(LiveEvent e){return new AiDecision("music_request","小宇","","");}};
    LiveRoom room=new LiveRoom(ai);MusicCatalog source=mock(MusicCatalog.class);MusicService music=new MusicService(source,room);BilibiliCaptureService capture=new BilibiliCaptureService(json,room,music,"node","missing");new SemanticMusicRouter(room,music,capture);
    try{room.subscribe("owner",true);room.accept(comment("deny","u","不要放小宇"));until(()->((Map<?,?>)room.status().get("current")).get("text").toString().contains("不修改队列"));assertTrue(songs(music).isEmpty());verifyNoInteractions(source);}finally{capture.shutdown();room.shutdown();}
  }
  @Test void bilibiliStopDuringSlowSemanticSearchDiscardsResult()throws Exception {
    CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
    AiReplyService ai=new AiReplyService(){public String reply(LiveEvent e){return "";}public boolean needsRemote(LiveEvent e){return true;}public AiDecision decide(LiveEvent e){return new AiDecision("music_request","小宇","","");}};
    LiveRoom room=new LiveRoom(ai);MusicCatalog source=mock(MusicCatalog.class);when(source.find("小宇")).thenAnswer(c->{started.countDown();release.await(2,TimeUnit.SECONDS);return new MusicCatalog.Song("1","小宇","歌手","","/fixture.wav",1000,"tencent");});
    MusicService music=new MusicService(source,room);BilibiliCaptureService capture=new BilibiliCaptureService(json,room,music,"node","missing");new SemanticMusicRouter(room,music,capture);ReflectionTestUtils.setField(capture,"state","live");ReflectionTestUtils.setField(capture,"roomId","123");
    try{room.subscribe("owner",true);capture.ingest(json.valueToTree(Map.of("platform","bilibili","id","one","roomId","123","type","chat","ts",System.currentTimeMillis(),"user",Map.of("id","901","nickname","夹具"),"data",Map.of("content","我想听小宇"))));assertTrue(started.await(1,TimeUnit.SECONDS));capture.disconnect();release.countDown();until(()->!((Map<?,?>)room.status().get("current")).get("text").toString().isBlank());assertTrue(songs(music).isEmpty());}finally{release.countDown();capture.shutdown();room.shutdown();}
  }
  @Test void commaCommandsAreAdmittedWithoutClaimingSemanticRecognition()throws Exception {
    AiReplyService ai=mock(AiReplyService.class);LiveRoom room=new LiveRoom(ai);MusicCatalog source=mock(MusicCatalog.class);when(source.find(anyString())).thenAnswer(c->new MusicCatalog.Song("id:"+c.getArgument(0),c.getArgument(0),"歌手","","/fixture.wav",1000,"tencent"));MusicService music=new MusicService(source,room);BilibiliCaptureService capture=new BilibiliCaptureService(json,room,music,"node","missing");ReflectionTestUtils.setField(capture,"state","live");ReflectionTestUtils.setField(capture,"roomId","123");
    try{for(int i=0;i<2;i++)capture.ingest(json.valueToTree(Map.of("platform","bilibili","id","comma"+i,"roomId","123","type","chat","ts",System.currentTimeMillis(),"user",Map.of("id",String.valueOf(901+i)),"data",Map.of("content",i==0?"点歌，小宇":"点歌,晴天"))));until(()->songs(music).size()==2);verify(source).find("小宇");verify(source).find("晴天");verify(ai,never()).reply(any());verify(ai,never()).decide(any());}finally{capture.shutdown();room.shutdown();}
  }
  @Test void ownerReportsAreRequiredForConfirmedPlayback() {
    LiveRoom room=new LiveRoom(new MockAiReplyService());MusicService music=new MusicService(q->new MusicCatalog.Song("id","测试曲","本地","","/fixture.wav",1000,"local"),room);
    try{room.subscribe("owner",true);room.subscribe("observer",true);music.request("点歌 测试曲");String id=songs(music).getFirst().entryId();assertEquals(false,((Map<?,?>)music.status().get("playback")).get("confirmed"));
      assertFalse(music.playback(id,"observer",room.ownerEpoch("owner"),"PLAYING",1));assertFalse(music.playback("old","owner",room.ownerEpoch("owner"),"PLAYING",1));assertTrue(music.playback(id,"owner",room.ownerEpoch("owner"),"LOADING",0));assertEquals(false,((Map<?,?>)music.status().get("playback")).get("confirmed"));
      assertTrue(music.progress(id,"owner",2));assertEquals(true,((Map<?,?>)music.status().get("playback")).get("confirmed"));music.pause(true);assertEquals(false,((Map<?,?>)music.status().get("playback")).get("confirmed"));
    }finally{room.shutdown();}
  }
  @Test void defaultCatalogUsesLocalFilesAndNeverCallsNetEase()throws Exception {
    Files.write(temp.resolve("验收提示音.wav"),wav());NeteaseMusicCatalog legacy=mock(NeteaseMusicCatalog.class);var source=new ConfiguredMusicCatalog(new MockEnvironment().withProperty("music.local-dir",temp.toString()),json,legacy);
    assertEquals("local",source.find("验收提示音").source());assertThrows(IllegalArgumentException.class,()->source.find("不存在"));verifyNoInteractions(legacy);
  }
  @Test void httpProviderResolvesAndProbesBeforeReturningOpaqueStream()throws Exception {
    AtomicInteger resolves=new AtomicInteger(),media=new AtomicInteger();AtomicReference<String> authorization=new AtomicReference<>();
    answer("/v1/search","application/json",()->"{\"items\":[{\"provider\":\"tencent\",\"id\":\"123\",\"title\":\"夹具提示音\",\"artist\":\"本地验收\"}]}");
    answer("/v1/resolve","application/json",()->{resolves.incrementAndGet();return "{\"url\":\""+base+"/audio\",\"headers\":{\"Authorization\":\"fixture-source-key\"},\"expiresAt\":"+(System.currentTimeMillis()+2000)+"}";});
    fixture.createContext("/audio",e->{try{media.incrementAndGet();authorization.set(e.getRequestHeaders().getFirst("Authorization"));e.getResponseHeaders().set("Content-Type","audio/wav");e.sendResponseHeaders(200,wav().length);e.getResponseBody().write(wav());}finally{e.close();}});
    var source=catalog(new MockEnvironment().withProperty("music.provider","http").withProperty("music.gateway-url",base).withProperty("music.allow-loopback-fixture","true"));var song=source.find("夹具提示音");
    assertEquals("tencent",song.source());assertTrue(song.audioUrl().startsWith("/api/music/stream/"));assertFalse(song.toString().contains("fixture-source-key"));assertEquals(1,resolves.get());assertEquals(1,media.get());
    song=source.prepare(song);String token=song.audioUrl().substring(song.audioUrl().lastIndexOf('/')+1);var audio=source.openStream(token,"bytes=0-");try(var stream=audio.body()){assertEquals(200,audio.statusCode());assertEquals('R',stream.read());}assertEquals(2,resolves.get());assertEquals("fixture-source-key",authorization.get());
    assertThrows(ProviderException.class,()->source.openStream(token,"bytes=0-1,3-4"));
  }
  @Test void jsonOrUnplayableAudioIsRejectedBeforeQueueing()throws Exception {
    answer("/v1/search","application/json",()->"{\"items\":[{\"provider\":\"tencent\",\"id\":\"123\",\"title\":\"不可播\"}]}");answer("/v1/resolve","application/json",()->"{\"url\":\""+base+"/audio\"}");answer("/audio","application/json",()->"{\"error\":\"login required\"}");
    var source=catalog(new MockEnvironment().withProperty("music.provider","http").withProperty("music.gateway-url",base).withProperty("music.allow-loopback-fixture","true"));assertThrows(IllegalArgumentException.class,()->source.find("不可播"));
  }
  @Test void awooAutoPlaybackSwitchNeverBecomesConfirmedSound() {
    answer("/api/v1/state","application/json",()->"{\"schemaVersion\":1,\"playing\":true,\"player\":{\"connected\":true},\"current\":{\"id\":\"1\",\"title\":\"当前曲\",\"artist\":\"歌手\"},\"queue\":[]}");
    var reader=new AwooStateController(new MockEnvironment().withProperty("music.awoo-state-url",base),json);Map<String,Object> result=reader.status();assertEquals(true,result.get("readOnly"));assertEquals(false,result.get("controlAvailable"));assertEquals(false,result.get("playbackConfirmed"));assertEquals(1,requests.get());
  }
  @Test void awooModeFailsExplicitlyAndHasNoSecretFallback() {
    NeteaseMusicCatalog legacy=mock(NeteaseMusicCatalog.class);var source=new ConfiguredMusicCatalog(new MockEnvironment().withProperty("music.provider","awoo"),json,legacy);assertThrows(IllegalArgumentException.class,()->source.find("小宇"));verifyNoInteractions(legacy);
  }
  @Test void ambiguousOrMismatchedSearchResultsNeverResolve() {
    AtomicReference<String> items=new AtomicReference<>("[{\"provider\":\"tencent\",\"id\":\"a\",\"title\":\"同名曲\",\"artist\":\"歌手甲\"},{\"provider\":\"tencent\",\"id\":\"b\",\"title\":\"同名曲\",\"artist\":\"歌手乙\"}]");
    answer("/v1/search","application/json",()->"{\"items\":"+items.get()+"}");
    var source=catalog(new MockEnvironment().withProperty("music.provider","http").withProperty("music.gateway-url",base));
    assertTrue(assertThrows(IllegalArgumentException.class,()->source.find("同名曲")).getMessage().contains("多个同名版本"));
    items.set("[{\"provider\":\"tencent\",\"id\":\"wrong\",\"title\":\"另一首歌\",\"artist\":\"歌手甲\"}]");
    assertTrue(assertThrows(IllegalArgumentException.class,()->source.find("同名曲")).getMessage().contains("不一致"));
    assertEquals(2,requests.get());
  }
  @Test void explicitArtistSelectsMatchingMetadataBeforeResolve()throws Exception {
    AtomicReference<String> selected=new AtomicReference<>(),search=new AtomicReference<>();
    fixture.createContext("/v1/search",e->{try{search.set(e.getRequestURI().getRawQuery());byte[] data="{\"items\":[{\"provider\":\"tencent\",\"id\":\"a\",\"title\":\"同名曲\",\"artist\":\"歌手甲\"},{\"provider\":\"tencent\",\"id\":\"b\",\"title\":\"同名曲\",\"artist\":\"歌手乙\"}]}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);}finally{e.close();}});
    fixture.createContext("/v1/resolve",e->{try{selected.set(e.getRequestURI().getRawQuery());byte[] data=("{\"url\":\""+base+"/audio\"}").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);}finally{e.close();}});
    fixture.createContext("/audio",e->{try{e.getResponseHeaders().set("Content-Type","audio/wav");e.sendResponseHeaders(200,wav().length);e.getResponseBody().write(wav());}finally{e.close();}});
    var source=catalog(new MockEnvironment().withProperty("music.provider","http").withProperty("music.gateway-url",base).withProperty("music.allow-loopback-fixture","true"));
    var song=source.find("同名曲","歌手乙");assertEquals("tencent:b",song.songId());assertEquals("歌手乙",song.artist());
    assertTrue(search.get().contains("artist="+java.net.URLEncoder.encode("歌手乙",StandardCharsets.UTF_8)));assertTrue(selected.get().contains("id=b"));
  }
  @Test void localMultipleMatchesRequireVersionInsteadOfFirstFilename()throws Exception {
    Files.write(temp.resolve("验收提示音-甲.wav"),wav());Files.write(temp.resolve("验收提示音-乙.wav"),wav());
    var source=catalog(new MockEnvironment().withProperty("music.local-dir",temp.toString()));
    assertTrue(assertThrows(IllegalArgumentException.class,()->source.find("验收提示音")).getMessage().contains("多个同名版本"));
    assertEquals("验收提示音-乙",source.find("验收提示音-乙").title());
  }
}

