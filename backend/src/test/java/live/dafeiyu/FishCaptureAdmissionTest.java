package live.dafeiyu;

import java.time.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FishCaptureAdmissionTest {
  @TempDir Path dir;
  @Test void roomRoutesTrustedCreditsToPersistentBalanceSeparatelyFromRunIncome() {
    var clock=new AtomicLong(10000);var ledger=new FishBalanceLedger(dir.resolve("fish-balance.json"),
        new ObjectMapper(),new BigDecimal("3.50"),"123456",clock::get);
    var room=new LiveRoom(new MockAiReplyService(),clock::get);
    var capture=new BilibiliCaptureService(new ObjectMapper(),room,mock(MusicService.class),"node","../collector/dist/bilibili/worker.js");
    try {
      room.fishBalance(ledger);room.captureChatStatus("bilibili","123456","live",true);
      ReflectionTestUtils.setField(capture,"roomId","123456");
      ReflectionTestUtils.setField(capture,"state","live");
      long at=clock.incrementAndGet();
      var event=new ObjectMapper().valueToTree(Map.of("platform","bilibili","roomId","123456","type","gift", "id","live-send","ts",at,
          "user",Map.of("id","123","nickname","fixture"),"data",Map.of("giftId","9","num",1,"countMode","delta","coinType","gold","totalCoin","1000","groupId","","repeatCount",0)));
      ReflectionTestUtils.invokeMethod(capture,"ingest",event,true);
      var finance=(Map<?,?>)room.status().get("finance");
      assertEquals(0,new BigDecimal("450").compareTo((BigDecimal)finance.get("balanceFen")));
      assertEquals(0,new BigDecimal("100").compareTo((BigDecimal)finance.get("incomeFen")));
      var combo=new ObjectMapper().valueToTree(Map.of("platform","bilibili","roomId","123456","type","gift","id","live-combo","ts",at,
          "user",Map.of("id","123","nickname","fixture"),"data",Map.of("giftId","9","num",0,"countMode","total","groupId","stable-combo","repeatCount",1)));
      ReflectionTestUtils.invokeMethod(capture,"ingest",combo,true);
      assertEquals(0,new BigDecimal("450").compareTo((BigDecimal)((Map<?,?>)room.status().get("finance")).get("balanceFen")),"Production inferred-to-stable group alias must preserve coverage");
      room.captureChatStatus("bilibili","123456","closed",true);at=clock.incrementAndGet();
      room.captureGift("bilibili","123456","closed-send",1,"delta",1,"gold","1000",at,"gift-group","",0);
      assertEquals(0,new BigDecimal("450").compareTo((BigDecimal)((Map<?,?>)room.status().get("finance")).get("balanceFen")));
    } finally {capture.shutdown();room.shutdown();}
  }
  @Test void onlyFreshTrustedSourceCanCreditAndMidnightDoesNotLoseADelayedGift() {
    long midnight=ZonedDateTime.of(2026,10,31,0,0,0,0,ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    var metrics=new CaptureChatMetrics();metrics.status("bilibili","123456","live",true,midnight-2000);
    assertFalse(metrics.admitsGiftBalance("bilibili","123456",midnight-3000,midnight-1000));
    metrics.snapshot(midnight+100);
    assertTrue(metrics.admitsGiftBalance("bilibili","123456",midnight-1000,midnight+100));
    assertFalse(metrics.admitsGiftBalance("bilibili","other",midnight,midnight+100));
    assertFalse(metrics.admitsGiftBalance("bilibili","123456",midnight-50000,midnight+100));
    metrics.status("bilibili","123456","reconnecting",true,midnight+200);
    assertFalse(metrics.admitsGiftBalance("bilibili","123456",midnight,midnight+200));
    metrics.status("bilibili","123456","live",false,midnight+300);
    assertFalse(metrics.admitsGiftBalance("bilibili","123456",midnight,midnight+300));
  }
}
