package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class DouyinCaptureTest {
  final ObjectMapper json = new ObjectMapper();
  LiveRoom room;
  DouyinCaptureService capture;
  @BeforeEach void setup() {
    room = mock(LiveRoom.class);
    capture = new DouyinCaptureService(json, room, "node", "missing-worker.js");
    ReflectionTestUtils.setField(capture, "roomId", "123");
  }
  @AfterEach void close() { capture.shutdown(); }
  void event(String id, String type, String user, String data) throws Exception {
    capture.ingest(json.readTree("{\"id\":\"" + id + "\",\"roomId\":\"123\",\"type\":\"" + type + "\",\"ts\":" + System.currentTimeMillis() + ",\"user\":{\"id\":\"" + user + "\",\"nickname\":\"观众小鱼\"},\"data\":" + data + "}"));
  }
  void gift(String id, int total, boolean end, String user) throws Exception {
    event(id, "gift", user, "{\"giftId\":\"9\",\"giftName\":\"小心心\",\"groupId\":\"888\",\"repeatCount\":" + total + ",\"repeatEnd\":" + end + "}");
  }
  @SuppressWarnings("unchecked") List<DouyinCaptureService.CaptureEvent> rows() { return (List<DouyinCaptureService.CaptureEvent>) capture.snapshot().get("events"); }

  @Test void roomLinksValidateHostAndNeverBecomeFetchTargets() {
    assertEquals("123", DouyinCaptureService.parseRoom(" https://live.douyin.com/123/?foo=1 "));
    assertEquals("123", DouyinCaptureService.parseRoom("123"));
    assertEquals("https://v.douyin.com/ExampleRoom/", DouyinCaptureService.parseRoom("复制链接打开抖音 https://v.douyin.com/ExampleRoom/ 5@0.com"));
    for (String input : List.of("https://evil.example/123", "https://live.douyin.com.evil/123", "https://user@live.douyin.com/123", "../../etc/passwd", "https://live.douyin.com/abc"))
      assertThrows(IllegalArgumentException.class, () -> DouyinCaptureService.parseRoom(input));
  }
  @Test void comboCountsOnlyIncreasesAndIgnoresFinalOrOutOfOrderFrames() throws Exception {
    gift("1", 1, false, "u1"); gift("2", 3, false, "u1"); gift("3", 3, true, "u1"); gift("4", 2, false, "u1");
    assertEquals(3L, capture.snapshot().get("gifts"));
    assertEquals(List.of(1L, 2L), rows().stream().map(DouyinCaptureService.CaptureEvent::giftCount).toList());
    verify(room, never()).accept(any());
    capture.flushGifts(System.currentTimeMillis()+2000);
    ArgumentCaptor<Protocol.LiveEvent> accepted=ArgumentCaptor.forClass(Protocol.LiveEvent.class);
    verify(room,times(1)).accept(accepted.capture());assertEquals(3,accepted.getValue().giftCount());assertEquals(Protocol.EventType.GIFT,accepted.getValue().type());
    gift("5",3,true,"u1");capture.flushGifts(System.currentTimeMillis()+3000);verify(room,times(1)).accept(any());
  }
  @Test void giftGroupsAreIsolatedByUser() throws Exception {
    gift("1", 3, false, "u1"); gift("2", 2, false, "u2");
    assertEquals(5L, capture.snapshot().get("gifts")); assertEquals(2, rows().size());
    capture.flushGifts(System.currentTimeMillis()+2000);verify(room,times(2)).accept(any());
  }
  @Test void missingGroupIdsStillUseDeltaCountsAndIgnoreEndingFrame()throws Exception{
    event("no-group-1","gift","u1","{\"giftId\":\"9\",\"combo\":true,\"repeatCount\":1}");
    event("no-group-2","gift","u1","{\"giftId\":\"9\",\"combo\":true,\"repeatCount\":3}");
    event("no-group-3","gift","u1","{\"giftId\":\"9\",\"combo\":true,\"repeatCount\":3,\"repeatEnd\":true}");
    capture.flushGifts(System.currentTimeMillis()+2000);ArgumentCaptor<Protocol.LiveEvent> accepted=ArgumentCaptor.forClass(Protocol.LiveEvent.class);verify(room,times(1)).accept(accepted.capture());assertEquals(3,accepted.getValue().giftCount());
    event("no-group-4","gift","u1","{\"giftId\":\"9\",\"combo\":true,\"repeatCount\":1}");
    event("no-group-5","gift","u1","{\"giftId\":\"9\",\"combo\":true,\"repeatCount\":2,\"repeatEnd\":true}");
    capture.flushGifts(System.currentTimeMillis()+2000);verify(room,times(2)).accept(accepted.capture());assertEquals(2,accepted.getValue().giftCount());assertEquals(5L,capture.snapshot().get("gifts"));
  }
  @Test void historicalGiftsAreVisibleButNeverThanked()throws Exception{
    capture.ingest(json.readTree("{\"id\":\"old-gift\",\"roomId\":\"123\",\"type\":\"gift\",\"ts\":"+(System.currentTimeMillis()-60000)+",\"data\":{\"repeatCount\":3,\"giftId\":\"9\",\"groupId\":\"old\",\"giftName\":\"小心心\"}}"));
    capture.flushGifts(System.currentTimeMillis()+2000);assertEquals(1,rows().size());verify(room,never()).accept(any());
  }
  @Test void capturesRapidCommentsWithoutSpeechRateLimitAndDeduplicatesReplays() throws Exception {
    event("9007199254740993111", "chat", "u1", "{\"content\":\"你好\"}");
    event("9007199254740993111", "chat", "u1", "{\"content\":\"你好\"}");
    event("9007199254740993112", "chat", "u1", "{\"content\":\"第二条\"}");
    assertEquals(2L, capture.snapshot().get("comments")); assertEquals(2, rows().size());
    assertEquals("观众小鱼", rows().getFirst().nickname()); assertTrue(rows().getFirst().id().endsWith("9007199254740993111"));
    ArgumentCaptor<Protocol.LiveEvent> accepted = ArgumentCaptor.forClass(Protocol.LiveEvent.class);
    verify(room, times(2)).accept(accepted.capture());
    assertEquals("你好", accepted.getAllValues().getFirst().content());
    assertEquals(Protocol.EventType.COMMENT, accepted.getValue().type());
    assertEquals("u1", accepted.getValue().userId());
    assertTrue(accepted.getValue().eventId().startsWith("douyin-123:chat:"));
    assertFalse(IntegrationConfig.automatic(accepted.getValue()));
  }
  @Test void historyAndBlankCommentsStayVisibleWithoutGeneratingSpeech() throws Exception {
    capture.ingest(json.readTree("{\"id\":\"old\",\"roomId\":\"123\",\"type\":\"chat\",\"ts\":" + (System.currentTimeMillis()-60000) + ",\"data\":{\"content\":\"过期弹幕\"}}"));
    event("blank", "chat", "u1", "{\"content\":\"   \"}");
    capture.ingest(json.readTree("{\"id\":\"old-entry\",\"roomId\":\"123\",\"type\":\"member\",\"ts\":" + (System.currentTimeMillis()-60000) + ",\"data\":{}}"));
    assertEquals(3, rows().size()); verify(room, never()).accept(any());
  }
  @Test void realArrivalRoutesToTemplateWelcomeOnceWithCloudTtsEligibleOrigin() throws Exception {
    event("entry1", "member", "viewer1", "{}");
    event("entry1", "member", "viewer1", "{}");
    ArgumentCaptor<Protocol.LiveEvent> accepted = ArgumentCaptor.forClass(Protocol.LiveEvent.class);
    verify(room, times(1)).accept(accepted.capture());
    Protocol.LiveEvent arrival = accepted.getValue();
    assertEquals(Protocol.EventType.USER_ENTER, arrival.type());
    assertEquals("观众小鱼", arrival.nickname()); assertEquals("viewer1", arrival.userId());
    assertFalse(IntegrationConfig.automatic(arrival));
    assertEquals(1L, capture.snapshot().get("members"));
  }
  @Test void clearingCaptureDoesNotReplayOldCommentsIntoSpeech() throws Exception {
    event("1", "chat", "u1", "{\"content\":\"你好\"}"); capture.clear();
    event("1", "chat", "u1", "{\"content\":\"你好\"}");
    verify(room, times(1)).accept(any()); assertTrue(rows().isEmpty());
  }
  @Test void clearKeepsDedupeAndComboProgress() throws Exception {
    gift("1", 3, false, "u1"); capture.clear(); gift("1", 3, false, "u1"); gift("2", 3, true, "u1"); gift("3", 4, false, "u1");
    assertEquals(1L, capture.snapshot().get("gifts")); assertEquals(1, rows().size());
  }
  @Test void ringBufferIsBoundedWithoutLosingCounters() throws Exception {
    for (int i = 0; i < 700; i++) event("" + i, "member", "u1", "{}");
    assertEquals(500, rows().size()); assertEquals(700L, capture.snapshot().get("members"));
    assertTrue(rows().getFirst().id().endsWith(":200"));
  }
  @Test void ignoresOtherRoomsAndUnsupportedTypes() throws Exception {
    capture.ingest(json.readTree("{\"id\":\"1\",\"roomId\":\"456\",\"type\":\"chat\"}"));
    event("2", "like", "u1", "{}"); assertTrue(rows().isEmpty());
  }
}
