package live.dafeiyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The local fish account: opening balance plus durable, deduplicated gift credits. */
@Component
public final class FishBalanceLedger {
  private record Gift(String group,String alias,long total,String mode,long num,String coin,String totalCoin) {}
  private final Path file;
  private final ObjectMapper json;
  private final String roomId;
  private final Map<String,Gift> events=new LinkedHashMap<>();
  private String accountId=UUID.randomUUID().toString(),reason="ready";
  private BigDecimal openingFen,balanceFen;
  private long createdAt,expected,reported;
  private boolean healthy=true,unpriced,incomplete;
  private volatile Map<String,Object> published=Map.of("available",false,"reason","initializing");

  @org.springframework.beans.factory.annotation.Autowired
  public FishBalanceLedger(IntegrationConfig config,ObjectMapper json,
      @Value("${finance.fish-opening-balance:0}") String opening,
      @Value("${finance.fish-room-id:}") String roomId) {
    this(config.dataDir.resolve("fish-balance.json"),json,new BigDecimal(opening),roomId,System::currentTimeMillis);
  }

  FishBalanceLedger(Path file,ObjectMapper json,BigDecimal openingFish,String roomId,LongSupplier clock) {
    this.file=file;this.json=json;this.roomId=roomId==null?"":roomId.strip();
    if(openingFish.signum()<0||openingFish.scale()>2||openingFish.precision()>14||(!this.roomId.isBlank()&&!this.roomId.matches("[1-9]\\d{0,29}")))
      throw new IllegalArgumentException("Invalid fish-account opening values");
    openingFen=openingFish.movePointRight(2);createdAt=clock.getAsLong();
    if(this.roomId.isBlank()){healthy=false;reason="room_not_configured";publish();return;}
    try {
      if(Files.exists(file))load();
      else {recount();save();}
    } catch(Exception error) {healthy=false;reason="ledger_read_or_initialize_failed";}
    publish();
  }

  synchronized void gift(String platform,String room,String id,String mode,long num,
      String coin,String totalCoin,long occurredAt,String group,String alias,long total) {
    if(!healthy||!"bilibili".equals(platform)||!roomId.equals(room)||occurredAt<createdAt
        ||id==null||id.isBlank()||id.length()>400||id.matches("(?i)^(mock|fixture|test|manual)[-_:].*")
        ||group==null||group.isBlank()||group.length()>400||alias!=null&&alias.length()>400||total<0)return;
    try {
      String prefix=platform+"\n"+room+"\n";
      String key=hash(prefix+id);
      if(events.containsKey(key))return;
      events.put(key,new Gift(hash(prefix+group),alias==null||alias.isBlank()?"":hash(prefix+alias),
          total,mode==null?"":mode,num,coin==null?"":coin,totalCoin));
      try {recount();save();}
      catch(Exception error) {events.remove(key);recount();healthy=false;reason="ledger_write_failed";}
    } catch(Exception error) {healthy=false;reason="ledger_credit_failed";}
    publish();
  }

  Map<String,Object> status() {return new LinkedHashMap<>(published);}

  private void publish() {
    boolean available=healthy&&!unpriced&&!incomplete;
    Map<String,Object> out=new LinkedHashMap<>();
    out.put("available",available);out.put("balanceFen",available?balanceFen:null);
    out.put("knownBalanceFen",healthy?balanceFen:null);out.put("openingFen",openingFen);
    out.put("accountId",accountId);out.put("createdAt",createdAt);out.put("roomId",roomId);
    out.put("recordedEvents",events.size());out.put("persistent",true);
    out.put("reason",!healthy?reason:unpriced||incomplete?"gift_amount_missing":"recorded_balance");
    published=Collections.unmodifiableMap(out);
  }

  private void recount() {
    balanceFen=openingFen;expected=reported=0;unpriced=incomplete=false;
    Map<String,String> parents=new HashMap<>();
    for(Gift gift:events.values()) {
      parents.putIfAbsent(gift.group(),gift.group());
      if(!gift.alias().isBlank()) {
        parents.putIfAbsent(gift.alias(),gift.alias());
        String current=root(parents,gift.group()),old=root(parents,gift.alias());
        if(!current.equals(old))parents.put(old,current);
      }
    }
    Map<String,long[]> coverage=new HashMap<>();
    for(Gift gift:events.values()) {
      long[] group=coverage.computeIfAbsent(root(parents,gift.group()),key->new long[2]);
      group[0]=Math.max(group[0],gift.total());
      if("total".equals(gift.mode()))continue;
      if(!"delta".equals(gift.mode())||gift.num()<=0){unpriced=true;continue;}
      group[1]=Math.addExact(group[1],gift.num());
      if("silver".equals(gift.coin()))continue;
      if(!"gold".equals(gift.coin())||gift.totalCoin()==null||!gift.totalCoin().matches("\\d{1,16}")){
        unpriced=true;continue;
      }
      balanceFen=balanceFen.add(new BigDecimal(gift.totalCoin()).movePointLeft(1));
    }
    for(long[] group:coverage.values()) {
      expected=Math.addExact(expected,group[0]);reported=Math.addExact(reported,group[1]);
      if(group[1]<group[0])incomplete=true;
    }
  }

  private static String root(Map<String,String> parents,String group) {
    String result=group;
    while(!parents.get(result).equals(result))result=parents.get(result);
    while(!group.equals(result)){String next=parents.get(group);parents.put(group,result);group=next;}
    return result;
  }

  private static String hash(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private void load() throws Exception {
    if(Files.size(file)>32L*1024*1024)throw new IllegalArgumentException("Ledger too large");
    JsonNode root=json.readTree(file.toFile());
    if(root.path("version").asInt()!=2||!roomId.equals(root.path("roomId").asText())
        ||!root.path("events").isObject()||!root.path("createdAt").isIntegralNumber())
      throw new IllegalArgumentException("Invalid fish ledger");
    openingFen=decimal(root.path("openingFen").asText());
    createdAt=root.path("createdAt").asLong();accountId=root.path("accountId").asText();
    if(createdAt<=0||accountId.isBlank())throw new IllegalArgumentException("Invalid account identity");
    var fields=root.path("events").fields();
    while(fields.hasNext()) {
      var field=fields.next();JsonNode value=field.getValue();
      if(!field.getKey().matches("[a-f0-9]{64}")||!value.path("group").asText().matches("[a-f0-9]{64}")
          ||!value.path("alias").asText().matches("(?:[a-f0-9]{64})?")||!value.path("total").isIntegralNumber()
          ||!value.path("num").isIntegralNumber()||value.path("total").asLong()<0)
        throw new IllegalArgumentException("Invalid receipt");
      events.put(field.getKey(),new Gift(value.path("group").asText(),value.path("alias").asText(),
          value.path("total").asLong(),value.path("mode").asText(),
          value.path("num").asLong(),value.path("coin").asText(),
          value.path("totalCoin").isNull()?null:value.path("totalCoin").asText()));
    }
    recount();
    if(balanceFen.compareTo(decimal(root.path("balanceFen").asText()))!=0)
      throw new IllegalArgumentException("Balance does not match recorded credits");
  }

  private static BigDecimal decimal(String value) {
    if(!value.matches("\\d{1,24}(\\.\\d{1,2})?"))throw new IllegalArgumentException("Invalid amount");
    return new BigDecimal(value);
  }

  private void save() throws Exception {
    Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"fish-balance-",".tmp");
    try {
      Map<String,Object> data=new LinkedHashMap<>();
      data.put("version",2);data.put("accountId",accountId);data.put("roomId",roomId);
      data.put("createdAt",createdAt);data.put("openingFen",openingFen.toPlainString());
      data.put("balanceFen",balanceFen.toPlainString());data.put("events",events);
      json.writeValue(temp.toFile(),data);
      Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    } finally {Files.deleteIfExists(temp);}
  }
}
