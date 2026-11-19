package live.dafeiyu;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Backend-only immutable snapshots. Never serialize secrets to clients. */
@Component
public final class IntegrationConfig {
  final Path dataDir;
  final HttpClient http;
  private final ObjectMapper json=new ObjectMapper();
  private final Path file;
  private final String deepseekBaseUrl,doubaoUrl;
  private volatile Snapshot active;
  private ObjectNode values;
  private long revision;

  public record Snapshot(String aiProvider,String deepseekKey,String deepseekModel,
      String deepseekBaseUrl,String ttsProvider,String doubaoAuthMode,String doubaoApiKey,
      String doubaoAppId,String doubaoAccessToken,String doubaoResourceId,String doubaoSpeaker,
      String doubaoUrl,int aiMaxTokens,int aiMaxChars,int aiDailyLimit,int ttsDailyLimit,
      int requestTimeoutSeconds,boolean paidAutoEnabled) {
    boolean deepseekReady(){return "deepseek".equals(aiProvider)&&!deepseekKey.isBlank();}
    boolean doubaoReady(){return "doubao".equals(ttsProvider)&&("apiKey".equals(doubaoAuthMode)?!doubaoApiKey.isBlank():!doubaoAppId.isBlank()&&!doubaoAccessToken.isBlank());}
    String aiFingerprint(){return ProviderStore.hash(deepseekBaseUrl+"|"+deepseekKey+"|"+deepseekModel+"|"+aiMaxTokens+"|"+aiMaxChars);}
    String ttsFingerprint(){return ProviderStore.hash(doubaoUrl+"|"+doubaoAuthMode+"|"+doubaoApiKey+"|"+doubaoAppId+"|"+doubaoAccessToken+"|"+doubaoResourceId+"|"+doubaoSpeaker);}
    @Override public String toString(){return "IntegrationSnapshot[redacted]";}
  }

  public IntegrationConfig(Environment env) {
    dataDir=Path.of(env.getProperty("integration.data-dir","../.runtime/providers")).toAbsolutePath().normalize();
    file=dataDir.resolve("runtime-config.json");
    deepseekBaseUrl=env.getProperty("integration.deepseek-base-url","https://api.deepseek.com");
    doubaoUrl=env.getProperty("integration.doubao-url","https://openspeech.bytedance.com/api/v3/tts/unidirectional");
    HttpClient.Builder builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);
    String proxy=env.getProperty("integration.proxy-url","").trim();
    if(!proxy.isEmpty()){URI uri=URI.create(proxy);builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(),uri.getPort())));}
    http=builder.build();
    values=json.createObjectNode();
    ObjectNode ai=values.putObject("ai"),tts=values.putObject("tts");
    ai.put("provider",env.getProperty("integration.ai-provider","mock"));
    ai.put("apiKey",env.getProperty("integration.deepseek-key","").trim());
    ai.put("model",env.getProperty("integration.deepseek-model","deepseek-flash"));
    ai.put("maxOutputTokens",Math.clamp(env.getProperty("integration.ai-max-tokens",Integer.class,96),32,96));
    ai.put("maxReplyChars",Math.clamp(env.getProperty("integration.ai-max-chars",Integer.class,45),15,45));
    ai.put("dailyRequestLimit",Math.max(0,env.getProperty("integration.ai-daily-limit",Integer.class,10)));
    String apiKey=env.getProperty("integration.doubao-api-key","").trim();
    tts.put("provider",env.getProperty("integration.tts-provider","browser"));
    String mode=env.getProperty("integration.doubao-auth-mode","").trim();
    tts.put("authMode",mode.isEmpty()?(!apiKey.isEmpty()||env.getProperty("integration.doubao-app-id","").isBlank()?"apiKey":"appToken"):mode);
    tts.put("apiKey",apiKey);tts.put("appId",env.getProperty("integration.doubao-app-id","").trim());
    tts.put("accessToken",env.getProperty("integration.doubao-access-token","").trim());
    tts.put("resourceId",env.getProperty("integration.doubao-resource-id","seed-tts-2.0"));
    tts.put("speaker",env.getProperty("integration.doubao-speaker","zh_female_gujie_uranus_bigtts"));
    tts.put("dailyCharLimit",Math.max(0,env.getProperty("integration.tts-daily-limit",Integer.class,500)));
    values.put("paidAutoEnabled",env.getProperty("integration.paid-auto-enabled",Boolean.class,false));
    values.put("timeoutSeconds",Math.clamp(env.getProperty("integration.timeout-seconds",Integer.class,20),2,60));
    try {
      if(Files.isRegularFile(file)){
        JsonNode saved=json.readTree(file.toFile());
        if(!saved.isObject())throw new IllegalArgumentException();
        merge(values,saved,false);revision=saved.path("revision").asLong(0);
      }
      active=validated(values);
    } catch(Exception failure){throw new IllegalStateException("Runtime provider configuration unreadable; repair the local configuration before starting");}
  }
  Snapshot snapshot(){return active;}
  boolean deepseekReady(){return active.deepseekReady();}
  boolean doubaoReady(){return active.doubaoReady();}
  public synchronized Map<String,Object> status(){
    Snapshot s=active;
    return Map.of("ai",Map.of("provider",s.aiProvider(),"model",s.deepseekModel(),"apiKeyConfigured",!s.deepseekKey().isBlank(),"maxOutputTokens",s.aiMaxTokens(),"maxReplyChars",s.aiMaxChars(),"dailyRequestLimit",s.aiDailyLimit()),
      "tts",Map.of("provider",s.ttsProvider(),"authMode",s.doubaoAuthMode(),"apiKeyConfigured",!s.doubaoApiKey().isBlank(),"appId",s.doubaoAppId(),"accessTokenConfigured",!s.doubaoAccessToken().isBlank(),"resourceId",s.doubaoResourceId(),"speaker",s.doubaoSpeaker(),"dailyCharLimit",s.ttsDailyLimit()),
      "paidAutoEnabled",s.paidAutoEnabled(),"timeoutSeconds",s.requestTimeoutSeconds(),"revision",revision);
  }
  public synchronized Map<String,Object> update(JsonNode patch){
    if(patch==null||!patch.isObject())throw invalid();
    ObjectNode next=values.deepCopy();merge(next,patch,true);Snapshot candidate=validated(next);
    next.put("revision",revision+1);
    AtomicJson.write(json,file,next,"CONFIG_WRITE_FAILED");
    values=next;active=candidate;revision++;return status();
  }
  private static void merge(ObjectNode target,JsonNode patch,boolean preserveEmptySecrets){
    Set<String> root=Set.of("ai","tts","paidAutoEnabled","timeoutSeconds","clearSecrets","revision");
    patch.fieldNames().forEachRemaining(key->{if(!root.contains(key))throw invalid();});
    for(String group:List.of("ai","tts"))if(patch.has(group)){
      JsonNode part=patch.get(group);if(!part.isObject())throw invalid();
      Set<String> fields=group.equals("ai")?Set.of("provider","model","apiKey","maxOutputTokens","maxReplyChars","dailyRequestLimit"):Set.of("provider","authMode","apiKey","appId","accessToken","resourceId","speaker","dailyCharLimit");
      part.fields().forEachRemaining(entry->{
        if(!fields.contains(entry.getKey()))throw invalid();
        JsonNode value=entry.getValue();
        if(preserveEmptySecrets&&Set.of("apiKey","accessToken").contains(entry.getKey())&&(value.isNull()||value.isTextual()&&value.asText().isBlank()))return;
        ((ObjectNode)target.get(group)).set(entry.getKey(),value);
      });
    }
    for(String key:List.of("paidAutoEnabled","timeoutSeconds"))if(patch.has(key))target.set(key,patch.get(key));
    if(patch.has("clearSecrets")){
      JsonNode clear=patch.get("clearSecrets");if(!clear.isArray())throw invalid();
      for(JsonNode key:clear){switch(key.asText()){
        case "deepseekApiKey" -> ((ObjectNode)target.get("ai")).put("apiKey","");
        case "doubaoApiKey" -> ((ObjectNode)target.get("tts")).put("apiKey","");
        case "doubaoAccessToken" -> ((ObjectNode)target.get("tts")).put("accessToken","");
        default -> throw invalid();
      }}
    }
  }
  private Snapshot validated(JsonNode n){
    JsonNode a=n.path("ai"),t=n.path("tts");
    String ap=choice(a,"provider",Set.of("mock","deepseek")),tp=choice(t,"provider",Set.of("browser","doubao"));
    return new Snapshot(ap,text(a,"apiKey",1024,true),text(a,"model",80,false),deepseekBaseUrl,tp,choice(t,"authMode",Set.of("apiKey","appToken")),text(t,"apiKey",1024,true),text(t,"appId",128,true),text(t,"accessToken",2048,true),text(t,"resourceId",100,false),text(t,"speaker",100,false),doubaoUrl,
      number(a,"maxOutputTokens",32,96),number(a,"maxReplyChars",15,45),number(a,"dailyRequestLimit",0,1000000),number(t,"dailyCharLimit",0,10000000),number(n,"timeoutSeconds",2,60),bool(n,"paidAutoEnabled"));
  }
  private static String text(JsonNode n,String key,int max,boolean blank){JsonNode v=n.path(key);if(!v.isTextual())throw invalid();String s=v.asText().trim();if(s.length()>max||(!blank&&s.isBlank())||s.chars().anyMatch(c->Character.isISOControl(c)))throw invalid();return s;}
  private static String choice(JsonNode n,String key,Set<String> choices){String v=text(n,key,50,false);if(!choices.contains(v))throw invalid();return v;}
  private static int number(JsonNode n,String key,int min,int max){JsonNode v=n.path(key);if(!v.isIntegralNumber()||!v.canConvertToInt()||v.asInt()<min||v.asInt()>max)throw invalid();return v.asInt();}
  private static boolean bool(JsonNode n,String key){if(!n.path(key).isBoolean())throw invalid();return n.path(key).asBoolean();}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("云端配置字段或范围不正确");}
  static boolean automatic(Protocol.LiveEvent event){return event!=null&&event.eventId()!=null&&event.eventId().startsWith("mock-");}
}
