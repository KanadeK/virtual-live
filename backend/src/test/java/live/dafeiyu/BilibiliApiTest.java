package live.dafeiyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
  "integration.ai-provider=mock", "integration.tts-provider=browser", "integration.ai-daily-limit=0", "integration.tts-daily-limit=0", "music.local-enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class BilibiliApiTest {
  static final Path data;
  static { try { data = Files.createTempDirectory("ds-bilibili-test-"); } catch(IOException e) { throw new ExceptionInInitializerError(e); } }
  @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
    p.add("integration.data-dir", () -> data.toString());
    p.add("bilibili.worker", () -> Path.of("src/test/resources/capture/worker.mjs").toAbsolutePath().toString());
    p.add("douyin.worker", () -> Path.of("src/test/resources/capture/worker.mjs").toAbsolutePath().toString());
  }
  @LocalServerPort int port;
  @Autowired ObjectMapper json;
  @Autowired LiveRoom room;
  @Autowired BilibiliCaptureService bilibili;
  @Autowired DouyinCaptureService douyin;
  @org.springframework.test.context.bean.override.mockito.MockitoBean MusicCatalog catalog;
  final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api" + path); }
  HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }
  JsonNode get(String path) throws Exception { return json.readTree(client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()); }
  void await(BooleanSupplier ready) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!ready.getAsBoolean()) { if (System.nanoTime() > deadline) fail("local capture fixture timed out"); Thread.sleep(15); }
  }
  void connect(String platform, String id) throws Exception {
    assertEquals(200, post("/"+platform+"/connect", "{\"roomId\":\""+id+"\"}").statusCode());
    await(() -> "live".equals(("bilibili".equals(platform) ? bilibili.snapshot() : douyin.snapshot()).get("state")));
  }
  @AfterEach void stop() { bilibili.disconnect(); douyin.disconnect(); }

  @Test void restSubprocessAndSseExposePlatformTagsAndNeverCredentials() throws Exception {
    List<JsonNode> captured = new CopyOnWriteArrayList<>();
    HttpResponse<InputStream> stream = client.send(HttpRequest.newBuilder(uri("/events/stream?clientId=bili-fixture-preview&role=preview")).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, stream.statusCode());
    Thread consumer = Thread.ofPlatform().daemon().start(() -> {
      try (var lines = new BufferedReader(new InputStreamReader(stream.body(), java.nio.charset.StandardCharsets.UTF_8))) {
        String line; while ((line=lines.readLine())!=null) if (line.startsWith("data:")) captured.add(json.readTree(line.substring(5)));
      } catch (IOException ignored) {}
    });
    try {
      String privateValue="SESSDATA=fixture-private-session; buvid3=fixture";
      HttpResponse<String> response=post("/bilibili/connect",json.writeValueAsString(Map.of("roomId","https://live.bilibili.com/123", "cookie",privateValue)));
      assertEquals(200,response.statusCode());assertFalse(response.body().contains("fixture-private-session"));
      await(()->((Long)bilibili.snapshot().get("comments"))==3);
      await(()->captured.stream().filter(e->e.path("type").asText().equals("CAPTURE_EVENT")).count()>=6);
      JsonNode status=get("/bilibili/status");
      assertEquals("bilibili",status.path("platform").asText());assertEquals("456",status.path("roomId").asText());
      assertEquals(3,status.path("gifts").asInt());assertEquals(1,status.path("members").asInt());assertTrue(status.path("hasLogin").asBoolean());
      assertEquals("9007199254740993111",status.path("events").get(0).path("userId").asText());
      assertFalse(status.toString().contains("fixture-private-session"));assertFalse(status.has("cookie"));
      for(JsonNode wire:captured) if(wire.path("type").asText().startsWith("CAPTURE_")) assertTrue(Set.of("douyin","bilibili").contains(wire.path("data").path("platform").asText()));
      assertEquals(200,post("/bilibili/clear","{}").statusCode());
      await(()->captured.stream().anyMatch(e->e.path("type").asText().equals("CAPTURE_RESET")&&e.path("data").path("platform").asText().equals("bilibili")));
      assertEquals(0,get("/bilibili/status").path("events").size());
      JsonNode usage=get("/integrations/status").path("usage");assertEquals(0,usage.path("counters").path("aiRequests").asLong());
      assertEquals(0,usage.path("counters").path("ttsRequests").asLong());assertEquals(0,usage.path("aiTotalTokens").asLong());
    } finally { stream.body().close(); room.release("bili-fixture-preview"); consumer.join(1500); }
  }
  @Test void songCommandsUseSharedMusicAndSseWhileSlowSearchDoesNotBlockJsonlOrHeartbeat() throws Exception {
    CountDownLatch searching = new CountDownLatch(1), release = new CountDownLatch(1);
    when(catalog.find("晴天")).thenAnswer(call -> {
      searching.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
      return new MusicCatalog.Song("fixture-song", "晴天", "本地夹具", "", "/fixture.wav", 1000);
    });
    List<JsonNode> captured = new CopyOnWriteArrayList<>();
    HttpResponse<InputStream> stream = client.send(HttpRequest.newBuilder(uri("/events/stream?clientId=song-preview&role=preview")).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    Thread consumer = Thread.ofPlatform().daemon().start(() -> {
      try (var lines = new BufferedReader(new InputStreamReader(stream.body(), java.nio.charset.StandardCharsets.UTF_8))) {
        String line; while ((line = lines.readLine()) != null) if (line.startsWith("data:")) captured.add(json.readTree(line.substring(5)));
      } catch (IOException ignored) {}
    });
    try {
      connect("bilibili", "960"); assertTrue(searching.await(2, TimeUnit.SECONDS));
      await(() -> ((Long)bilibili.snapshot().get("frames")) == 77);
      assertEquals(5, get("/bilibili/status").path("comments").asInt());
      assertEquals(2, get("/status").path("queueSize").asInt());
      assertEquals(200, post("/client/heartbeat", "{\"clientId\":\"song-preview\"}").statusCode());
      assertEquals(0, get("/music/status").path("playlist").size());
      await(() -> captured.stream().anyMatch(e -> e.path("type").asText().equals("MUSIC_UPDATE") && e.path("data").path("notice").asText().contains("间隔4秒")));
      release.countDown();
      await(() -> captured.stream().anyMatch(e -> e.path("type").asText().equals("MUSIC_UPDATE") && e.path("data").path("notice").asText().equals("已添加：晴天")));
      JsonNode status = get("/music/status"); assertEquals(1, status.path("playlist").size());
      assertEquals("fixture-song", status.path("current").path("song").path("songId").asText());
      verify(catalog, times(1)).find("晴天"); verifyNoMoreInteractions(catalog);
      JsonNode usage = get("/integrations/status").path("usage");
      assertEquals(0, usage.path("counters").path("aiRequests").asLong()); assertEquals(0, usage.path("counters").path("ttsRequests").asLong());
    } finally { release.countDown(); stream.body().close(); room.release("song-preview"); consumer.join(1500); }
  }
  @Test void invalidConnectAndLoginDoNotStopCurrentValidSource() throws Exception {
    connect("douyin","940");
    for(String operation:List.of("connect","login")) {
      assertEquals(400,post("/bilibili/"+operation,"{\"roomId\":\"https://evil.test/123\"}").statusCode());
      assertEquals("live",douyin.snapshot().get("state"));
    }
    assertEquals(400,post("/bilibili/connect","{\"roomId\":\"123\",\"cookie\":\"bad\\nHeader:x\"}").statusCode());
    assertEquals("live",douyin.snapshot().get("state"));
    connect("bilibili","940");
    assertEquals(400,post("/douyin/connect","{\"roomId\":\"https://evil.test/123\"}").statusCode());
    assertEquals("live",bilibili.snapshot().get("state")); assertEquals("stopped",douyin.snapshot().get("state"));
  }
  @Test void simultaneousConnectsLeaveOnlyOneSourceAndStoppedWorkersCannotDeliverLateEvents() throws Exception {
    List<CompletableFuture<Void>> jobs=new ArrayList<>();
    for(int i=0;i<8;i++) {
      String platform=i%2==0?"douyin":"bilibili";
      jobs.add(CompletableFuture.runAsync(()->{try{assertEquals(200,post("/"+platform+"/connect","{\"roomId\":\"930\"}").statusCode());}catch(Exception e){throw new CompletionException(e);}}));
    }
    CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);
    await(()->"live".equals(bilibili.snapshot().get("state"))||"live".equals(douyin.snapshot().get("state")));
    long active=List.of(bilibili.snapshot(),douyin.snapshot()).stream().filter(s->Set.of("live","connecting","reconnecting").contains(s.get("state"))).count();assertEquals(1,active);
    post("/douyin/disconnect","{}");post("/bilibili/disconnect","{}");
    long beforeBili=(Long)bilibili.snapshot().get("comments"),beforeDouyin=(Long)douyin.snapshot().get("comments");
    Thread.sleep(450);
    assertEquals(beforeBili,bilibili.snapshot().get("comments"));assertEquals(beforeDouyin,douyin.snapshot().get("comments"));
  }
  @Test void switchAndDisconnectDropPendingGiftThanksInBothAdapters() throws Exception {
    connect("bilibili","920");await(()->((Long)bilibili.snapshot().get("gifts"))==3);
    connect("douyin","940");bilibili.flushGifts(System.currentTimeMillis()+3000);assertTrue(((List<?>)room.status().get("queue")).isEmpty());
    connect("douyin","920");await(()->((Long)douyin.snapshot().get("gifts"))==3);
    connect("bilibili","940");douyin.flushGifts(System.currentTimeMillis()+3000);assertTrue(((List<?>)room.status().get("queue")).isEmpty());
    assertEquals("stopped",douyin.snapshot().get("state"));
  }
  @Test void platformRejectionStatesNeverForwardPreAuthEventsAndCanRecoverThroughNormalLoginFixture() throws Exception {
    for(var scenario:Map.of("910","LOGIN_REQUIRED","911","ROOM_OFFLINE","912","RATE_LIMITED","913","AUTH_FAILED").entrySet()) {
      post("/bilibili/connect","{\"roomId\":\""+scenario.getKey()+"\"}");
      await(()->scenario.getValue().equals(bilibili.snapshot().get("errorCode")));
      assertNotEquals("live",bilibili.snapshot().get("state"));assertEquals("LOGIN_REQUIRED".equals(scenario.getValue()),bilibili.snapshot().get("requiresLogin"));
      assertTrue(((List<?>)bilibili.snapshot().get("events")).isEmpty());
    }
    assertEquals(200,post("/bilibili/login","{\"roomId\":\"940\"}").statusCode());
    await(()->"live".equals(bilibili.snapshot().get("state")));
    assertEquals("success",bilibili.snapshot().get("loginState"));assertEquals(true,bilibili.snapshot().get("hasLogin"));
    assertFalse(get("/bilibili/status").toString().contains("private-fixture-only"));
  }
  @Test void cancelOrSwitchClosesPendingLoginAndOldLoginCannotAutoConnect() throws Exception {
    post("/bilibili/login","{\"roomId\":\"950\"}");await(()->"waiting".equals(bilibili.snapshot().get("loginState")));
    post("/bilibili/login/cancel","{}");assertEquals("idle",bilibili.snapshot().get("loginState"));
    post("/bilibili/login","{\"roomId\":\"950\"}");await(()->"waiting".equals(bilibili.snapshot().get("loginState")));
    connect("douyin","940");Thread.sleep(100);
    assertEquals("stopped",bilibili.snapshot().get("state"));assertEquals("idle",bilibili.snapshot().get("loginState"));assertEquals("live",douyin.snapshot().get("state"));
  }
}
