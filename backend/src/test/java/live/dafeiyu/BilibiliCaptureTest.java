package live.dafeiyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BilibiliCaptureTest {
  final ObjectMapper json = new ObjectMapper();
  LiveRoom room;
  BilibiliCaptureService capture;
  @BeforeEach void setup() {
    room = mock(LiveRoom.class); capture = new BilibiliCaptureService(json, room, mock(MusicService.class), "node", "missing-worker.js");
    ReflectionTestUtils.setField(capture, "roomId", "9007199254740993333");
  }
  @AfterEach void close() { capture.shutdown(); }
  void event(String id, String type, String data, long ts) throws Exception {
    capture.ingest(json.readTree("{\"platform\":\"bilibili\",\"id\":\"" + id + "\",\"roomId\":9007199254740993333,\"type\":\"" + type
      + "\",\"ts\":" + ts + ",\"user\":{\"id\":9007199254740993111,\"nickname\":\"观众小鱼\"},\"data\":" + data + "}"));
  }
  void gift(String id, String group, int num, int total, boolean summary, long timestamp) throws Exception {
    event(id, "gift", "{\"giftId\":\"9\",\"giftName\":\"小花\",\"groupId\":\"" + group + "\",\"num\":" + num + ",\"repeatCount\":" + total + ",\"countMode\":\"" + (summary ? "total" : "delta") + "\"}", timestamp);
  }
  @SuppressWarnings("unchecked") List<BilibiliCaptureService.CaptureEvent> rows() { return (List<BilibiliCaptureService.CaptureEvent>)capture.snapshot().get("events"); }
  @Test void roomInputsAreStrictAndNeverBecomeFetchTargets() {
    assertEquals("123", BilibiliCaptureService.parseRoom(" https://live.bilibili.com/123/?x=1 "));
    assertEquals("123", BilibiliCaptureService.parseRoom("123"));
    for (String value : List.of("0", "-1", "../../etc/passwd", "https://evil.test/123", "https://live.bilibili.com.evil/123", "https://user@live.bilibili.com/123", "https://live.bilibili.com:80/123", "https://live.bilibili.com/%31", "https://live.bilibili.com/a/../123"))
      assertThrows(IllegalArgumentException.class, () -> BilibiliCaptureService.parseRoom(value));
  }
  @Test void rawNumericInt64IdsStayExactAndCommentsRouteOnce() throws Exception {
    event("9007199254740993222", "chat", "{\"content\":\"你好\"}", System.currentTimeMillis());
    event("9007199254740993222", "chat", "{\"content\":\"你好\"}", System.currentTimeMillis());
    assertEquals("9007199254740993111", rows().getFirst().userId()); assertEquals("9007199254740993333", rows().getFirst().roomId());
    assertEquals("bilibili", rows().getFirst().platform()); assertEquals(1L, capture.snapshot().get("comments"));
    var accepted = ArgumentCaptor.forClass(Protocol.LiveEvent.class); verify(room).accept(accepted.capture());
    assertEquals("9007199254740993111", accepted.getValue().userId()); assertTrue(accepted.getValue().eventId().startsWith("bilibili-"));
    assertEquals(Protocol.EventType.COMMENT, accepted.getValue().type()); assertFalse(IntegrationConfig.automatic(accepted.getValue()));
  }
  @Test void deltaAndCumulativeGiftFramesOnlyThankNetIncrease() throws Exception {
    long now = System.currentTimeMillis();
    gift("tx1", "g1", 1, 1, false, now); gift("tx2", "g1", 2, 3, false, now);
    gift("end3", "g1", 0, 3, true, 0); gift("older2", "g1", 0, 2, true, 0); gift("end3-replay", "g1", 0, 3, true, 0);
    assertEquals(3L, capture.snapshot().get("gifts")); assertEquals(List.of(1L,2L), rows().stream().map(BilibiliCaptureService.CaptureEvent::giftCount).toList());
    verify(room, never()).accept(any()); capture.flushGifts(now + 2200);
    var accepted = ArgumentCaptor.forClass(Protocol.LiveEvent.class); verify(room).accept(accepted.capture());
    assertEquals(3, accepted.getValue().giftCount()); assertEquals(Protocol.EventType.GIFT, accepted.getValue().type());
    gift("end3-final", "g1", 0, 3, true, 0); capture.flushGifts(now + 4000); verify(room, times(1)).accept(any());
  }
  @Test void summaryBeforeLateTransactionsCannotDoubleThank() throws Exception {
    long now = System.currentTimeMillis();
    gift("summary3", "g1", 0, 3, true, now); gift("late-tx1", "g1", 1, 0, false, now); gift("late-tx2", "g1", 2, 0, false, now);
    capture.flushGifts(now + 2200); var accepted = ArgumentCaptor.forClass(Protocol.LiveEvent.class); verify(room).accept(accepted.capture()); assertEquals(3, accepted.getValue().giftCount());
  }
  @Test void ungroupedV2TransactionsAndGroupedSummaryShareProgressInEitherOrder() throws Exception {
    long now = System.currentTimeMillis();
    gift("v2-tx1", "", 1, 0, false, now); gift("v2-tx2", "", 2, 0, false, now);
    gift("summary3", "g1", 0, 3, true, now); gift("v2-tx4", "", 1, 0, false, now);
    gift("end4", "g1", 0, 4, true, 0); gift("end3", "g1", 0, 3, true, 0);
    assertEquals(4L, capture.snapshot().get("gifts"));capture.flushGifts(now+2200);
    var accepted=ArgumentCaptor.forClass(Protocol.LiveEvent.class);verify(room).accept(accepted.capture());assertEquals(4,accepted.getValue().giftCount());
    gift("summary5", "g2", 0, 5, true, now);gift("late-v2", "", 5, 0, false, now);
    assertEquals(9L,capture.snapshot().get("gifts"));capture.flushGifts(now+2200);verify(room,times(2)).accept(accepted.capture());assertEquals(5,accepted.getValue().giftCount());
  }
  @Test void separateTransactionsOfSameGiftMergeIntoOneThanks() throws Exception {
    long now = System.currentTimeMillis(); gift("tx1", "", 1, 0, false, now); gift("tx2", "", 2, 0, false, now);
    capture.flushGifts(now + 2200); var accepted = ArgumentCaptor.forClass(Protocol.LiveEvent.class); verify(room).accept(accepted.capture()); assertEquals(3, accepted.getValue().giftCount());
  }
  @Test void timestampLessUnknownSummaryAndOldEventsNeverGenerateSpeech() throws Exception {
    long now = System.currentTimeMillis(); gift("summary", "unknown", 0, 3, true, 0);
    gift("old-gift", "old", 2, 2, false, now - 60000); gift("old-end", "old", 0, 5, true, 0);
    event("old-comment", "chat", "{\"content\":\"历史评论\"}", now - 60000);
    event("old-entry", "member", "{}", now - 60000); event("future-entry", "member", "{}", now + 60000);
    event("blank", "chat", "{\"content\":\"   \"}", now);
    capture.flushGifts(now + 2200); verify(room, never()).accept(any()); assertEquals(7, rows().size());
  }
  @Test void clearAndDisconnectKeepDedupeAndComboHistoryButStopPendingThanks() throws Exception {
    long now = System.currentTimeMillis(); gift("tx1", "g1", 3, 3, false, now); capture.clear(); capture.disconnect();
    gift("tx1", "g1", 3, 3, false, now); gift("end3", "g1", 0, 3, true, 0);
    capture.flushGifts(now + 2200); assertTrue(rows().isEmpty()); verify(room, never()).accept(any());
    verify(room).captureReset("bilibili");
  }
  @Test void arrivalsAreRealTemplateEventsAndUnsupportedOrOtherPlatformsAreIgnored() throws Exception {
    long now = System.currentTimeMillis(); event("entry", "member", "{}", now); event("follow", "follow", "{}", now);
    JsonNode other = json.readTree("{\"platform\":\"douyin\",\"id\":\"x\",\"roomId\":\"9007199254740993333\",\"type\":\"chat\"}");capture.ingest(other);
    var accepted = ArgumentCaptor.forClass(Protocol.LiveEvent.class); verify(room).accept(accepted.capture());
    assertEquals(Protocol.EventType.USER_ENTER, accepted.getValue().type()); assertFalse(IntegrationConfig.automatic(accepted.getValue())); assertEquals(1, rows().size());
  }
  @Test void ringBufferHas500RowsAndCookieNeverEntersStatus() throws Exception {
    for (int i=0; i<650; i++) event("entry"+i, "member", "{}", 0);
    assertEquals(500, rows().size()); assertEquals(650L, capture.snapshot().get("members")); assertFalse(json.writeValueAsString(capture.snapshot()).contains("cookie"));
  }
  @Test void realRoomUsesAiOnlyForCommentsAndCorpusForGiftsAndMergedArrivalsUnderOneLease() throws Exception {
    var clock = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    AiReplyService ai = mock(AiReplyService.class);
    when(ai.needsRemote(any())).thenAnswer(call -> ((Protocol.LiveEvent)call.getArgument(0)).type() == Protocol.EventType.COMMENT);
    when(ai.reply(any())).thenReturn("本地AI夹具回复"); when(ai.status()).thenReturn(Map.of());
    LiveRoom actual = new LiveRoom(ai, clock::get);
    var temp = java.nio.file.Files.createTempDirectory("bili-routing-corpus-");
    actual.corpus(new CorpusService(new IntegrationConfig(new org.springframework.mock.env.MockEnvironment().withProperty("integration.data-dir",temp.toString())),json));
    BilibiliCaptureService adapter = new BilibiliCaptureService(json, actual, mock(MusicService.class), "node", "missing.js");
    ReflectionTestUtils.setField(adapter,"roomId","123");
    java.util.function.BiConsumer<String,String> feed=(id,type)->adapter.ingest(json.valueToTree(Map.of("platform","bilibili","id",id,"roomId","123","type",type,"ts",clock.get(),
      "user",Map.of("id",id.equals("entry2")?"22":"11","nickname",id.equals("entry2")?"小红":"小明"),
      "data",type.equals("gift")?Map.of("giftId","9","giftName","小花","groupId","g1","num",3,"repeatCount",3,"countMode","delta"):Map.of("content","你好"))));
    try {
      feed.accept("comment","chat");feed.accept("entry1","member");feed.accept("entry2","member");feed.accept("gift","gift");
      adapter.flushGifts(clock.get()+2000); clock.addAndGet(1700);actual.tick();verify(ai,never()).reply(any());
      actual.subscribe("preview",false);actual.subscribe("owner",true);actual.subscribe("observer",true);
      @SuppressWarnings("unchecked") Map<String,Object> first=(Map<String,Object>)actual.status().get("current");
      assertTrue(first.get("text").toString().contains("3"));assertTrue(first.get("text").toString().contains("小花"));
      String giftTask=first.get("taskId").toString();assertFalse(actual.authorizeSpeech(giftTask,"owner").automatic());
      assertThrows(ProviderException.class,()->actual.authorizeSpeech(giftTask,"observer"));assertFalse(actual.complete(giftTask,"preview"));
      assertTrue(actual.complete(giftTask,"owner"));verify(ai,timeout(1000).times(1)).reply(any());
      Map<?,?> comment=Map.of();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      while(!"本地AI夹具回复".equals(comment.get("text"))&&System.nanoTime()<deadline){comment=(Map<?,?>)actual.status().get("current");Thread.sleep(5);}
      assertEquals("本地AI夹具回复",comment.get("text"));assertTrue(actual.complete(comment.get("taskId").toString(),"owner"));
      Map<?,?> welcome=(Map<?,?>)actual.status().get("current");assertTrue(welcome.get("text").toString().contains("小明、小红"));
      assertFalse(actual.authorizeSpeech(welcome.get("taskId").toString(),"owner").automatic());verify(ai,times(1)).reply(any());
    } finally {adapter.shutdown();actual.shutdown();}
  }
}
