package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real capture -> room admission -> shared music queue, with no external services. */
class BilibiliSongCommandTest {
  final ObjectMapper json = new ObjectMapper();
  final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
  AiReplyService ai;
  LiveRoom room;
  MusicCatalog catalog;
  MusicService music;
  BilibiliCaptureService capture;
  @BeforeEach void setup() {
    ai = mock(AiReplyService.class);
    when(ai.needsRemote(any())).thenReturn(true);
    when(ai.reply(any())).thenReturn("AI夹具回复");
    when(ai.status()).thenReturn(Map.of());
    room = spy(new LiveRoom(ai, clock::get));
    catalog = mock(MusicCatalog.class);
    when(catalog.find(anyString())).thenAnswer(call -> song(call.getArgument(0)));
    music = new MusicService(catalog, room);
    capture = new BilibiliCaptureService(json, room, music, "node", "src/test/resources/capture/worker.mjs");
    ReflectionTestUtils.setField(capture, "roomId", "9007199254740993333");
    ReflectionTestUtils.setField(capture, "state", "live");
  }
  @AfterEach void close() { capture.shutdown(); room.shutdown(); }
  MusicCatalog.Song song(String name) { return new MusicCatalog.Song(name, name, "本地测试", "", "/fixture/" + name, 1000); }
  void chat(String id, String user, String content) { chat(id, user, content, System.currentTimeMillis()); }
  void chat(String id, String user, String content, long timestamp) {
    capture.ingest(json.valueToTree(Map.of("platform", "bilibili", "id", id, "roomId", "9007199254740993333", "type", "chat", "ts", timestamp,
      "user", Map.of("id", user, "nickname", "夹具观众"), "data", Map.of("content", content))));
  }
  @SuppressWarnings("unchecked") List<MusicService.Entry> songs() { return (List<MusicService.Entry>)music.status().get("playlist"); }
  void await(BooleanSupplier ready) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!ready.getAsBoolean()) { if (System.nanoTime() > deadline) fail("song routing fixture timed out"); Thread.sleep(5); }
  }
  void drained() throws Exception {
    ((ExecutorService)Objects.requireNonNull(ReflectionTestUtils.getField(capture, "songRequests"))).submit(() -> {}).get(3, TimeUnit.SECONDS);
  }
  void waitForRelease(CountDownLatch release) throws InterruptedException { assertTrue(release.await(3, TimeUnit.SECONDS)); }

  @Test void explicitCommandsJoinManualQueueAndRetainLocalFallbackAndOneOwner() throws Exception {
    music.localBackground(List.of(new MusicCatalog.Song("local", "背景", "本地", "", "/local", 1000, "local")));
    music.repeat(false); room.subscribe("owner", true); room.subscribe("observer", true);
    music.request("点歌 手动歌曲");
    String[] commands = {"点歌 晴天", " \t点歌\t稻香 ", "点歌：夜曲", "点歌　：　青花瓷", "点歌\u00a0七里香", "点歌: 最长的电影"};
    for (int n = 0; n < commands.length; n++) chat("command" + n, "90071992547409931" + n, commands[n]);
    await(() -> songs().size() == 7); drained();
    assertEquals(List.of("手动歌曲", "晴天", "稻香", "夜曲", "青花瓷", "七里香", "最长的电影"), songs().stream().map(e -> e.song().title()).toList());
    assertEquals("netease", music.status().get("source"));
    verify(ai, never()).needsRemote(any()); verify(ai, never()).reply(any());
    assertTrue(((List<?>)room.status().get("queue")).isEmpty()); assertTrue(((Map<?,?>)room.status().get("current")).isEmpty());
    assertFalse(music.completed(songs().getFirst().entryId(), "observer", false));
    while (!songs().isEmpty()) assertTrue(music.completed(songs().getFirst().entryId(), "owner", false));
    assertEquals("local", music.status().get("source")); assertEquals(true, music.status().get("fallbackActive"));
    verify(room, atLeastOnce()).musicUpdate(argThat(status -> status.get("notice").equals("已添加：晴天")));
  }
  @Test void questionsEmbeddedTextAndEmptyCommandsRemainOrdinaryAiComments() throws Exception {
    room.subscribe("owner", true);
    String[] comments = {"可以点歌吗", "我想点歌 晴天", "点歌吗", "点歌", "点歌   ", "点歌 ：　", "点歌：", "点歌晴天"};
    for (int n = 0; n < comments.length; n++) {
      chat("ordinary" + n, String.valueOf(100 + n), comments[n]);
      await(() -> "AI夹具回复".equals(((Map<?,?>)room.status().get("current")).get("text")));
      assertTrue(room.complete(((Map<?,?>)room.status().get("current")).get("taskId").toString(), "owner"));
    }
    drained(); verify(catalog, never()).find(anyString()); verify(ai, times(comments.length)).reply(any());
  }
  @Test void replayClearHistoryFutureAndForeignSourceNeverSearchAgain() throws Exception {
    long now = System.currentTimeMillis();
    chat("valid", "9007199254740993111", "点歌 晴天", now); await(() -> songs().size() == 1);
    var command = org.mockito.ArgumentCaptor.forClass(Protocol.LiveEvent.class);
    verify(room).acceptMusicCommand(command.capture()); assertEquals("9007199254740993111", command.getValue().userId());
    capture.clear(); chat("valid", "9007199254740993111", "点歌 晴天", now);
    chat("old", "22", "点歌 历史歌曲", now - 60000); chat("future", "33", "点歌 未来歌曲", now + 60000);
    capture.ingest(json.valueToTree(Map.of("platform", "douyin", "id", "foreign", "roomId", "9007199254740993333", "type", "chat", "ts", now,
      "user", Map.of("id", "44"), "data", Map.of("content", "点歌 其他平台"))));
    drained(); verify(catalog, times(1)).find("晴天"); verifyNoMoreInteractions(catalog); verify(ai, never()).reply(any());
  }
  @Test void musicAndChatShareFourSecondViewerThrottleAndPlatformKeys() throws Exception {
    chat("first", "9007199254740993111", "点歌 晴天"); await(() -> songs().size() == 1);
    chat("limited-song", "9007199254740993111", "点歌 稻香");
    chat("limited-chat", "9007199254740993111", "普通聊天");
    drained(); verify(catalog, times(1)).find(anyString()); verify(ai, never()).needsRemote(any());
    verify(room).musicUpdate(argThat(status -> status.get("notice").toString().contains("间隔4秒")));
    clock.addAndGet(4001); chat("next-chat", "9007199254740993111", "四秒后的普通聊天");
    chat("limited-by-chat", "9007199254740993111", "点歌 稻香"); drained(); verify(catalog, times(1)).find(anyString());
    assertEquals(1, ((List<?>)room.status().get("queue")).size());
    assertEquals(true, room.accept(new Protocol.LiveEvent("douyin-independent", Protocol.EventType.COMMENT, "9007199254740993111", "观众", "其他平台", "", 0, clock.get())).get("accepted"));
    clock.addAndGet(4001); chat("next-song", "9007199254740993111", "点歌 稻香"); await(() -> songs().size() == 2);
    verify(catalog).find("稻香");
  }
  @Test void fullPlaylistSkipsSearchAndDuplicatesAndSearchErrorsPublishMusicNotices() throws Exception {
    for (int n = 0; n < MusicService.LIMIT; n++) music.request("点歌 填充" + n);
    clearInvocations(catalog);
    chat("full", "1", "点歌 满队列"); drained(); verify(catalog, never()).find(anyString());
    verify(room).musicUpdate(argThat(status -> status.get("notice").toString().contains("最多 20 首")));
    music.clear(); chat("one", "2", "点歌 同一首"); await(() -> songs().size() == 1);
    chat("duplicate", "3", "点歌 同一首"); drained(); assertEquals(1, songs().size());
    verify(room).musicUpdate(argThat(status -> status.get("notice").toString().contains("已在播放列表")));
    when(catalog.find("搜索失败")).thenThrow(new IllegalArgumentException("网易云搜索连接失败，请稍后重试"));
    chat("failure", "4", "点歌 搜索失败"); drained();
    verify(room).musicUpdate(argThat(status -> status.get("notice").toString().contains("搜索连接失败")));
    when(catalog.find("意外失败")).thenThrow(new IllegalStateException("private diagnostic must stay private"));
    chat("unexpected", "5", "点歌 意外失败"); drained();
    assertEquals("B站点歌搜索暂不可用，请稍后重试", music.status().get("notice"));
    chat("recover", "6", "点歌 恢复"); await(() -> songs().size() == 2); verify(ai, never()).reply(any());
  }
  @Test void slowSearchHasOneWorkerAndEightWaitingRequestsWhileRoomRemainsResponsive() throws Exception {
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    when(catalog.find("慢歌曲")).thenAnswer(call -> { started.countDown(); waitForRelease(release); return song("慢歌曲"); });
    try {
      chat("slow", "1", "点歌 慢歌曲"); assertTrue(started.await(1, TimeUnit.SECONDS));
      assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
        for (int n = 0; n < 12; n++) chat("flood" + n, String.valueOf(100 + n), "点歌 等待" + n);
        room.subscribe("owner", true); assertTrue(room.heartbeat("owner")); room.tick();
        chat("ordinary", "99", "普通聊天仍继续");
        assertEquals(14L, capture.snapshot().get("comments"));
      });
      verify(catalog, times(1)).find(anyString());
      verify(room, times(4)).musicUpdate(argThat(status -> status.get("notice").toString().contains("请求较多")));
      verify(ai, timeout(1000)).reply(any()); release.countDown(); await(() -> songs().size() == 9); drained();
      verify(catalog, times(9)).find(anyString());
    } finally { release.countDown(); }
  }
  @Test void disconnectDropsPendingRequestsAndLateSuccessWithoutBlocking() throws Exception { stopWhileSearching(false); }
  @Test void sourceSwitchDropsPendingRequestsAndLateFailureWithoutChangingMusicNotice() throws Exception { stopWhileSearching(true); }
  @Test void manualFillDuringSearchCannotOverflowTheSharedPlaylist() throws Exception {
    for (int n = 0; n < 19; n++) music.request("点歌 预填" + n);
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    when(catalog.find("慢歌曲")).thenAnswer(call -> { started.countDown(); waitForRelease(release); return song("慢歌曲"); });
    try {
      chat("slow-fill", "1", "点歌 慢歌曲"); assertTrue(started.await(1, TimeUnit.SECONDS));
      music.request("点歌 手动最后一首"); release.countDown(); drained();
      assertEquals(20, songs().size()); assertEquals("手动最后一首", songs().getLast().song().title());
      assertTrue(music.status().get("notice").toString().contains("最多 20 首"));
    } finally { release.countDown(); }
  }
  @Test void queuedAndInFlightRequestsExpireBeforeSearchOrCommit() throws Exception {
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    when(catalog.find("即将过期")).thenAnswer(call -> { started.countDown(); waitForRelease(release); return song("即将过期"); });
    long timestamp = System.currentTimeMillis() - 44000;
    try {
      chat("expire-running", "1", "点歌 即将过期", timestamp); assertTrue(started.await(1, TimeUnit.SECONDS));
      chat("expire-waiting", "2", "点歌 排队过期", timestamp);
      Thread.sleep(Math.max(1, timestamp + 45020 - System.currentTimeMillis()));
      release.countDown(); drained(); assertTrue(songs().isEmpty());
      verify(catalog).find("即将过期"); verify(catalog, never()).find("排队过期");
      verify(room, never()).musicUpdate(any());
    } finally { release.countDown(); }
  }
  @Test void reconnectingSameRoomInvalidatesOldSearchEvenAfterItIsLiveAgain() throws Exception {
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    when(catalog.find("旧连接")).thenAnswer(call -> { started.countDown(); waitForRelease(release); return song("旧连接"); });
    try {
      chat("reconnect-old", "1", "点歌 旧连接"); assertTrue(started.await(1, TimeUnit.SECONDS));
      chat("reconnect-pending", "2", "点歌 旧等待");
      capture.connect("9007199254740993333", ""); await(() -> "live".equals(capture.snapshot().get("state")));
      release.countDown(); drained(); assertTrue(songs().isEmpty());
      chat("reconnect-old", "1", "点歌 旧连接");
      chat("reconnect-new", "3", "点歌 新连接"); await(() -> songs().size() == 1);
      assertEquals("新连接", songs().getFirst().song().title());
      verify(catalog, times(1)).find("旧连接"); verify(catalog, never()).find("旧等待");
    } finally { release.countDown(); }
  }
  void stopWhileSearching(boolean switchSource) throws Exception {
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    when(catalog.find("旧房间")).thenAnswer(call -> {
      started.countDown(); waitForRelease(release);
      if (switchSource) throw new IllegalArgumentException("旧房间迟到失败");
      return song("旧房间");
    });
    DouyinCaptureService douyin = new DouyinCaptureService(json, room, "node", "src/test/resources/capture/worker.mjs");
    try {
      chat("old-running", "1", "点歌 旧房间"); assertTrue(started.await(1, TimeUnit.SECONDS));
      chat("old-waiting", "2", "点歌 旧等待");
      assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
        if (switchSource) new CaptureSwitch(douyin, capture).connect("douyin", "940", "");
        else capture.disconnect();
      });
      music.requestNotice("新状态"); release.countDown(); drained();
      assertTrue(songs().isEmpty()); assertEquals("新状态", music.status().get("notice"));
      verify(catalog, times(1)).find("旧房间"); verify(catalog, never()).find("旧等待");
    } finally { release.countDown(); douyin.shutdown(); }
  }
}
