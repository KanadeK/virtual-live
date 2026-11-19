package live.dafeiyu;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.io.IOException;
import org.springframework.stereotype.Component;

/** Conservative persisted local caps, not a substitute for the providers' billing statements. */
@Component
public final class UsageLedger {
  private final IntegrationConfig config;private final ObjectMapper json;private final Path file;
  private final UsageCostEstimate estimate=new UsageCostEstimate();
  private String date;private final Map<String,Long> counters=new HashMap<>();
  public UsageLedger(IntegrationConfig config,ObjectMapper json){this.config=config;this.json=json;file=config.dataDir.resolve("usage.json");
    try{if(Files.isRegularFile(file)){Map<String,Object> saved=json.readValue(file.toFile(),new TypeReference<>(){});date=String.valueOf(saved.get("date"));Object values=saved.get("counters");if(values instanceof Map<?,?> map)map.forEach((k,v)->{if(v instanceof Number n)counters.put(String.valueOf(k),n.longValue());});}}
    catch(IOException e){throw new IllegalStateException("Usage ledger unreadable; refusing paid requests until repaired");}
    rollover();
  }
  private void rollover(){String today=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();if(!today.equals(date)){date=today;counters.clear();}}
  private long value(String key){return counters.getOrDefault(key,0L);}
  public synchronized void reserveAi(){reserveAi(config.snapshot().aiDailyLimit());}
  public synchronized void reserveAi(int limit){rollover();if(value("aiRequests")>=limit)throw new ProviderException("AI_DAILY_LIMIT",429);add("aiRequests",1);estimate.begin();}
  public synchronized void reserveTts(int characters){reserveTts(characters,config.snapshot().ttsDailyLimit());}
  public synchronized void reserveTts(int characters,int limit){rollover();if(value("ttsRequestedCharacters")+characters>limit)throw new ProviderException("TTS_DAILY_LIMIT",429);counters.put("ttsRequests",value("ttsRequests")+1);add("ttsRequestedCharacters",characters);estimate.begin();}
  public synchronized void recordAiTokens(long prompt,long completion,long hit){rollover();counters.put("promptTokens",value("promptTokens")+Math.max(0,prompt));counters.put("completionTokens",value("completionTokens")+Math.max(0,completion));counters.put("promptCacheHitTokens",value("promptCacheHitTokens")+Math.max(0,hit));save();}
  public synchronized void recordAiTokens(long prompt,long completion,long hit,String model,String responseModel,String endpoint,long at,long created,boolean present){
    recordAiTokens(prompt,completion,hit);estimate.ai(prompt,completion,hit,model,responseModel,endpoint,at,created,present);
  }
  public synchronized void recordTtsBilled(long billed,String resource,String endpoint,long at,boolean present){
    add("ttsBilledCharacters",Math.max(0,billed));estimate.tts(billed,resource,endpoint,at,present);
  }
  public synchronized void add(String key,long amount){rollover();counters.put(key,value(key)+amount);save();}
  public synchronized Map<String,Object> status(){rollover();Map<String,Object> result=new HashMap<>();result.put("date",date);result.put("counters",Map.copyOf(counters));result.put("aiTotalTokens",value("promptTokens")+value("completionTokens"));result.put("aiDailyRequestLimit",config.snapshot().aiDailyLimit());result.put("ttsDailyCharLimit",config.snapshot().ttsDailyLimit());result.put("costEstimate",estimate.snapshot(config.snapshot().deepseekReady()||config.snapshot().doubaoReady()));result.put("note","本机保守计数；失败请求的实际计费以服务商账单为准");return result;}
  private void save(){try{Files.createDirectories(file.getParent());Path tmp=Files.createTempFile(file.getParent(),"usage-",".tmp");json.writeValue(tmp.toFile(),Map.of("date",date,"counters",counters));Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){throw new ProviderException("USAGE_LEDGER_WRITE_FAILED",500);}}
}
