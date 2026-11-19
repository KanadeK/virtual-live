package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class FishBalanceLedgerTest {
  @TempDir Path dir;
  private final ObjectMapper json=new ObjectMapper();
  private FishBalanceLedger ledger(){return new FishBalanceLedger(dir.resolve("fish-balance.json"),json,new BigDecimal("3.50"),"123456",()->10000);}
  private void amount(String expected,Object actual){assertNotNull(actual);assertEquals(0,new BigDecimal(expected).compareTo((BigDecimal)actual));}

  @Test void unconfiguredRoomDoesNotCreateOrCreditAnAccount() {
    Path file=dir.resolve("unconfigured.json");
    var value=new FishBalanceLedger(file,json,BigDecimal.ZERO,"",()->10000);
    assertEquals(false,value.status().get("available"));
    assertEquals("room_not_configured",value.status().get("reason"));
    assertNull(value.status().get("balanceFen"));assertFalse(Files.exists(file));
    value.gift("bilibili","123456","live-send","delta",1,"gold","1000",10001,"group","",0);
    assertFalse(Files.exists(file));assertEquals(0,value.status().get("recordedEvents"));
  }

  @Test void openingPersistsAndNeverChangesTheProviderUsageFile() throws Exception {
    Path usage=dir.resolve("usage.json");String original="{\"date\":\"2026-10-31\",\"counters\":{\"aiRequests\":7}}";
    Files.writeString(usage,original);var first=ledger();amount("350",first.status().get("balanceFen"));
    var reopened=new FishBalanceLedger(dir.resolve("fish-balance.json"),json,BigDecimal.ZERO,"123456",()->20000);
    amount("350",reopened.status().get("balanceFen"));assertEquals(original,Files.readString(usage));
    assertEquals(first.status().get("accountId"),reopened.status().get("accountId"));
  }

  @Test void exactGiftTotalIsCreditedOnceEvenAfterRestart() {
    var first=ledger();first.gift("bilibili","123456","live-send-1","delta",2,"gold","1000",10001,"gift-group","",0);
    amount("450",first.status().get("balanceFen"));
    var reopened=ledger();reopened.gift("bilibili","123456","live-send-1","delta",2,"gold","9000",10001,"gift-group","",0);
    amount("450",reopened.status().get("balanceFen"));
    reopened.gift("bilibili","123456","live-send-2","delta",1,"gold","550",10002,"gift-group","",0);
    amount("505",reopened.status().get("balanceFen"));
  }

  @Test void comboCoverageSilverAndUnpricedMoneyRemainDistinctAcrossRestart() {
    var value=ledger();value.gift("bilibili","123456","combo-1","total",0,"",null,10001,"gift-group","",3);
    assertNull(value.status().get("balanceFen"));
    value.gift("bilibili","123456","send-1","delta",3,"gold","1234",10002,"gift-group","",0);
    amount("473.4",value.status().get("balanceFen"));
    value.gift("bilibili","123456","silver-1","delta",2,"silver","1000",10003,"gift-group","",0);
    amount("473.4",value.status().get("balanceFen"));
    value.gift("bilibili","123456","missing-amount","delta",1,"gold",null,10004,"gift-group","",0);
    assertNull(value.status().get("balanceFen"));amount("473.4",value.status().get("knownBalanceFen"));
    assertNull(ledger().status().get("balanceFen"));
  }

  @Test void wrongRoomLocalMockAndPreOpeningEventsCannotCreditTheAccount() {
    var value=ledger();
    value.gift("bilibili","other","live-send-1","delta",1,"gold","1000",10001,"gift-group","",0);
    value.gift("local","123456","live-send-2","delta",1,"gold","1000",10001,"gift-group","",0);
    value.gift("bilibili","123456","mock-local","delta",1,"gold","1000",10001,"gift-group","",0);
    value.gift("bilibili","123456","old-send","delta",1,"gold","1000",9999,"gift-group","",0);
    amount("350",value.status().get("balanceFen"));assertEquals(0,value.status().get("recordedEvents"));
  }

  @Test void cumulativeSixAfterRestartAddsOnlyTheOneNewTransactionAndRemainsAvailable() {
    var before=ledger();before.gift("bilibili","123456","send-five","delta",5,"gold","5000",10001,"stable-combo","",5);
    amount("850",before.status().get("balanceFen"));
    var after=ledger();after.gift("bilibili","123456","send-six","delta",1,"gold","1000",10002,"stable-combo","",6);
    amount("950",after.status().get("balanceFen"));
    amount("950",ledger().status().get("balanceFen"));
  }

  @Test void corruptExistingFileNeverResetsToTheOpeningBalance() throws Exception {
    Path file=dir.resolve("fish-balance.json");Files.writeString(file,"{broken");var value=ledger();
    assertEquals(false,value.status().get("available"));assertNull(value.status().get("balanceFen"));
    value.gift("bilibili","123456","live-send","delta",1,"gold","1000",10001,"gift-group","",0);
    assertEquals("{broken",Files.readString(file));
  }

  @Test void productionSpringConstructorUsesTheConfiguredOpeningAndIsUnambiguous() {
    var env=new MockEnvironment().withProperty("integration.data-dir",dir.toString());var config=new IntegrationConfig(env);
    try(var context=new AnnotationConfigApplicationContext()) {
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("opening",Map.of(
          "finance.fish-opening-balance","3.50","finance.fish-room-id","123456")));
      context.registerBean(IntegrationConfig.class,()->config);context.registerBean(ObjectMapper.class,()->json);
      context.register(FishBalanceLedger.class);context.refresh();
      amount("350",context.getBean(FishBalanceLedger.class).status().get("balanceFen"));
    }
  }
}
