package live.dafeiyu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;

class CaptureChatMetricsTest {
  private final long now=ZonedDateTime.of(2026,10,30,18,0,0,0,ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
  private void chat(CaptureChatMetrics stats,String id,String user,long at){stats.chat("bilibili","123",id,user,"公开聊天",at,at);}
  @Test void onlyAnObservedNormalCaptureCanProveZero(){
    var s=new CaptureChatMetrics();assertNull(s.snapshot(now).get("uniqueChatters"));
    s.status("bilibili","123","connecting",true,now);assertNull(s.snapshot(now).get("uniqueChatters"));
    s.status("bilibili","123","live",false,now);chat(s,"chat-a","9",now+1);assertNull(s.snapshot(now).get("uniqueChatters"));
    s.status("bilibili","123","live",true,now+2);assertEquals(0,s.snapshot(now+2).get("uniqueChatters"));
    assertFalse(CaptureChatMetrics.normalWorker("bilibili",Path.of("test-fixtures/worker.mjs").toAbsolutePath()));
    assertTrue(CaptureChatMetrics.normalWorker("bilibili",Path.of("../collector/dist/bilibili/worker.js").toAbsolutePath().normalize()));
  }
  @Test void stableIdentityDedupeAndHistoryNeverUseThe500RowFeed(){
    var s=new CaptureChatMetrics();s.status("bilibili","123","live",true,now);
    chat(s,"chat-a","1",now+1);chat(s,"chat-b","1",now+2);chat(s,"chat-a","2",now+3);
    s.chat("bilibili","123","old","3","历史",now-1,now+4);
    s.chat("bilibili","123","future","3","未来",now+20000,now+4);
    chat(s,"mock-local","3",now+4);chat(s,"chat-c","fixture-user",now+4);
    s.chat("bilibili","other","room","3","错房间",now+4,now+4);
    for(int i=2;i<=602;i++)chat(s,"chat-"+i,String.valueOf(i),now+10+i);
    assertEquals(602,s.snapshot(now+1000).get("uniqueChatters"));
    assertEquals("capture_run",s.snapshot(now+1000).get("scope"));
  }
  @Test void reconnectAndPageRefreshPreserveTheRangeButNewCaptureResetsIt(){
    var s=new CaptureChatMetrics();s.status("bilibili","123","live",true,now);chat(s,"chat-a","1",now+1);
    s.status("douyin","","stopped",true,now+2);assertEquals(1,s.snapshot(now+2).get("uniqueChatters"));
    s.status("bilibili","123","reconnecting",true,now+3);s.status("bilibili","123","live",true,now+4);
    chat(s,"chat-a","1",now+5);assertEquals(1,s.snapshot(now+6).get("uniqueChatters"));assertEquals(true,s.snapshot(now+6).get("hadGaps"));
    s.status("bilibili","123","stopped",true,now+7);s.status("bilibili","123","connecting",true,now+8);
    assertNull(s.snapshot(now+8).get("uniqueChatters"));s.status("bilibili","123","live",true,now+9);assertEquals(0,s.snapshot(now+9).get("uniqueChatters"));
  }
  @Test void shanghaiDateRolloverDoesNotInventFullDayCoverage(){
    var s=new CaptureChatMetrics();s.status("bilibili","123","live",true,now);chat(s,"a","1",now+1);
    long tomorrow=now+86400000;s.status("bilibili","123","live",true,tomorrow);
    assertEquals(0,s.snapshot(tomorrow).get("uniqueChatters"));assertEquals(tomorrow,s.snapshot(tomorrow).get("startedAt"));assertEquals("2026-10-31",s.snapshot(tomorrow).get("date"));
    s.status("bilibili","123","stopped",true,tomorrow+1);assertNull(s.snapshot(tomorrow+86400000).get("uniqueChatters"));
  }
  @Test void localRoomApiAndDirectTestIngestHaveNoAudienceHook(){
    var room=new LiveRoom(new MockAiReplyService(),()->now);
    room.captureEvent(java.util.Map.of("type","chat","userId","999"));
    var stats=(java.util.Map<?,?>)room.status().get("audience");assertEquals(false,stats.get("available"));assertNull(stats.get("uniqueChatters"));
    room.shutdown();
  }
}
