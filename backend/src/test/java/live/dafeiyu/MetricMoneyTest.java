package live.dafeiyu;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;

class MetricMoneyTest {
  private long saturday(){return ZonedDateTime.of(2026,10,31,10,0,0,0,ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();}
  private void money(BigDecimal expected,Object actual){assertEquals(0,expected.compareTo((BigDecimal)actual));}
  @Test void exactTransactionTotalNotPriceTimesCountOrCombo(){
    var s=new GiftPushValue();s.event("send-1",5,"delta",5,"gold","1000");
    s.event("send-1",5,"delta",5,"gold","1000");s.event("combo",0,"total",0,"gold","5000");
    money(new BigDecimal("100"),s.snapshot(true).get("incomeFen"));
  }
  @Test void comboFirstRequiresTheActualTransactionAndMissingAmountIsNotZero(){
    var s=new GiftPushValue();s.event("combo",3,"total",0,"","null");assertNull(s.snapshot(true).get("incomeFen"));
    s.event("send",0,"delta",3,"gold","1234");money(new BigDecimal("123.4"),s.snapshot(true).get("incomeFen"));
    s.event("v2-no-money",1,"delta",1,"",null);assertNull(s.snapshot(true).get("incomeFen"));
  }
  @Test void freeUnknownAndAbsentCaptureAreDifferentFromKnownEmptyZero(){
    var s=new GiftPushValue();assertNull(s.snapshot(false).get("incomeFen"));money(BigDecimal.ZERO,s.snapshot(true).get("incomeFen"));
    s.event("silver",1,"delta",1,"silver","1000");assertNull(s.snapshot(true).get("incomeFen"));
    s.clear();s.event("other",1,"delta",1,"copper","0");assertNull(s.snapshot(true).get("incomeFen"));
  }
  @Test void cacheHitMissOutputAndTtsUnitsUseVerifiedSeparateRates(){
    var e=new UsageCostEstimate();e.begin();e.ai(1000000,500000,100000,"deepseek-flash","deepseek-flash","https://api.deepseek.com",saturday(),0,true);
    e.begin();e.tts(1000,"seed-tts-2.0","https://openspeech.bytedance.com/api/v3/tts/unidirectional",saturday(),true);
    money(new BigDecimal("320.2"),e.snapshot(true).get("costFen"));assertEquals(false,e.snapshot(true).get("upperBound"));
  }
  @Test void incompleteUnknownModelAndLocalFixturesNeverBecomeFreeUsage(){
    var e=new UsageCostEstimate();money(BigDecimal.ZERO,e.snapshot(true).get("costFen"));e.begin();assertNull(e.snapshot(true).get("costFen"));
    e.ai(100,10,0,"unknown-model","unknown-model","https://api.deepseek.com",saturday(),0,true);assertNull(e.snapshot(true).get("costFen"));
    var t=new UsageCostEstimate();t.begin();t.tts(0,"seed-tts-2.0","https://openspeech.bytedance.com",saturday(),false);assertNull(t.snapshot(true).get("costFen"));
    var local=new UsageCostEstimate();local.begin();local.ai(100,10,0,"deepseek-flash","deepseek-flash","http://127.0.0.1:19999",saturday(),0,true);assertNull(local.snapshot(true).get("costFen"));
  }
  @Test void weekdayPeakIsExplicitlyAnUpperBoundWithoutAHolidayCalendar(){
    var e=new UsageCostEstimate();long friday=saturday()-86400000;e.begin();e.ai(1000000,0,0,"deepseek-flash","deepseek-flash","https://api.deepseek.com",friday,0,true);
    money(new BigDecimal("200"),e.snapshot(true).get("costFen"));assertEquals(true,e.snapshot(true).get("upperBound"));
  }
  @Test void pricingDoesNotChangeTheRawLedgerSchemaCountersOrCacheCharging() throws Exception {
    var dir=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"metric-ledger-");
    var env=new org.springframework.mock.env.MockEnvironment().withProperty("integration.data-dir",dir.toAbsolutePath().toString());
    var json=new com.fasterxml.jackson.databind.ObjectMapper();var ledger=new UsageLedger(new IntegrationConfig(env),json);
    ledger.reserveAi(100);ledger.recordAiTokens(100,10,20,"deepseek-flash","deepseek-flash","https://api.deepseek.com",saturday(),0,true);
    var before=ledger.status().get("costEstimate");ledger.add("aiCacheHits",1);ledger.add("ttsCacheHits",1);
    var after=(java.util.Map<?,?>)ledger.status().get("costEstimate");
    assertEquals(((java.util.Map<?,?>)before).get("knownSubtotalFen"),after.get("knownSubtotalFen"));
    var raw=json.readTree(dir.resolve("usage.json").toFile());assertEquals(2,raw.size());assertTrue(raw.has("date")&&raw.has("counters"));
    assertEquals(100,raw.path("counters").path("promptTokens").asLong());assertEquals(20,raw.path("counters").path("promptCacheHitTokens").asLong());
    assertFalse(raw.has("costFen"));assertFalse(raw.path("counters").has("costFen"));
  }
}
