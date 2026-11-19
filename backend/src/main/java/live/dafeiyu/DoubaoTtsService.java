package live.dafeiyu;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.io.*;
import org.springframework.stereotype.Service;

/** Official V3 HTTP chunked synthesis. PCM is wrapped as WAV for local playback. */
@Service
public final class DoubaoTtsService {
  private final IntegrationConfig settings;private final ObjectMapper json;private final UsageLedger usage;private final ProviderStore cache;
  private volatile String lastError="";private volatile String circuitScope="";private volatile long lastSuccessAt,lastLatencyMs,blockedUntil;
  public DoubaoTtsService(IntegrationConfig settings,ObjectMapper json,UsageLedger usage,ProviderStore cache){this.settings=settings;this.json=json;this.usage=usage;this.cache=cache;}
  public synchronized Map<String,Object> synthesize(String text,double rate,double pitch,String voice,boolean automatic){
    var config=settings.snapshot();String scope=config.ttsFingerprint();if(!scope.equals(circuitScope)){circuitScope=scope;blockedUntil=0;lastError="";}
    if(!"doubao".equals(config.ttsProvider()))return fallback("BROWSER_SELECTED",false);
    if(automatic&&!config.paidAutoEnabled())return fallback("AUTO_LOCAL_ONLY",false);
    if(!config.doubaoReady())return fallback(config.doubaoApiKey().isEmpty()&&config.doubaoAppId().isEmpty()?"DOUBAO_APP_ID_REQUIRED":"DOUBAO_CREDENTIALS_MISSING",false);
    if(text.isBlank()||text.codePointCount(0,text.length())>160||!InputFilter.safe(text))return fallback("TTS_TEXT_REJECTED",false);
    String speaker=voice==null||voice.isBlank()?config.doubaoSpeaker():voice;
    int speed=(int)Math.clamp(Math.round((rate-1)*100),-50,100);
    int semitones=(int)Math.clamp(Math.round(12*Math.log(pitch)/Math.log(2)),-12,12);
    String key=ProviderStore.hash(config.ttsFingerprint()+'|'+speaker+'|'+speed+'|'+semitones+'|'+text);
    var cached=cache.read("tts",key,".wav",86400000L);
    if(cached.isPresent()){usage.add("ttsCacheHits",1);return result(key,cached.get().length-44,true,0,speaker);}
    if(System.currentTimeMillis()<blockedUntil)return fallback("DOUBAO_CIRCUIT_OPEN",false);
    long requestAt=System.currentTimeMillis(),started=System.nanoTime();
    try{
      usage.reserveTts(text.codePointCount(0,text.length()),config.ttsDailyLimit());
      Map<String,Object> params=Map.of("text",text,"speaker",speaker,"audio_params",Map.of("format","pcm","sample_rate",24000,"speech_rate",speed),"additions",json.writeValueAsString(Map.of("post_process",Map.of("pitch",semitones))));
      HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(config.doubaoUrl())).timeout(Duration.ofSeconds(config.requestTimeoutSeconds())).header("Content-Type","application/json").header("X-Api-Resource-Id",config.doubaoResourceId()).header("X-Api-Request-Id",UUID.randomUUID().toString()).header("X-Control-Require-Usage-Tokens-Return","text_words");
      if("apiKey".equals(config.doubaoAuthMode()))builder.header("X-Api-Key",config.doubaoApiKey());
      else builder.header("X-Api-App-Id",config.doubaoAppId()).header("X-Api-Access-Key",config.doubaoAccessToken());
      HttpRequest request=builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("user",Map.of("uid","dafeiyu-live"),"req_params",params)))).build();
      HttpResponse<String> response=settings.http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if(response.statusCode()!=200)throw new ProviderException("DOUBAO_HTTP_"+response.statusCode(),response.statusCode());
      if(response.body().length()>10000000)throw new ProviderException("DOUBAO_AUDIO_TOO_LARGE",502);
      List<JsonNode> messages=new ArrayList<>();
      if(response.body().stripLeading().startsWith("event:")){
        for(String line:response.body().split("\\R"))if(line.startsWith("data:"))messages.add(json.readTree(line.substring(5).trim()));
      }else{try(MappingIterator<JsonNode> values=json.readerFor(JsonNode.class).readValues(response.body())){while(values.hasNextValue())messages.add(values.nextValue());}}
      ByteArrayOutputStream audio=new ByteArrayOutputStream();boolean finished=false;long billed=-1;
      for(JsonNode message:messages){
        int code=message.path("code").asInt(-1);
        if(code!=0&&code!=20000000)throw new ProviderException("DOUBAO_CODE_"+code,code==45000000?403:502);
        if(message.path("data").isTextual())audio.write(Base64.getDecoder().decode(message.path("data").asText()));
        if(audio.size()>5760000)throw new ProviderException("DOUBAO_AUDIO_TOO_LARGE",502);
        if(code==20000000){finished=true;billed=message.path("usage").path("text_words").asLong(-1);}
      }
      byte[] pcm=audio.toByteArray();if(!finished||pcm.length<2||pcm.length%2!=0)throw new ProviderException("DOUBAO_INCOMPLETE_AUDIO",502);
      cache.write("tts",key,".wav",wav(pcm));usage.recordTtsBilled(billed,config.doubaoResourceId(),config.doubaoUrl(),requestAt,billed>=0);lastSuccessAt=System.currentTimeMillis();lastLatencyMs=(System.nanoTime()-started)/1000000;lastError="";
      return result(key,pcm.length,false,Math.max(0,billed),speaker);
    }catch(Exception error){ProviderException safe=error instanceof ProviderException p?p:new ProviderException(error instanceof HttpTimeoutException?"DOUBAO_TIMEOUT":"DOUBAO_REQUEST_FAILED",503);
      lastError=safe.code;blockedUntil=safe.status==401||safe.status==403?Long.MAX_VALUE:System.currentTimeMillis()+30000;return fallback(safe.code,true);
    }
  }
  private Map<String,Object> result(String key,int bytes,boolean hit,long billed,String speaker){return Map.of("provider","doubao","audioUrl","/api/tts/audio/"+key,"contentType","audio/wav","durationSeconds",bytes/48000.0,"cacheHit",hit,"billedCharacters",billed,"voice",speaker);}
  private Map<String,Object> fallback(String reason,boolean count){if(count)usage.add("ttsFallbacks",1);return Map.of("provider","browser","reason",reason);}
  byte[] audio(String key){return cache.read("tts",key,".wav",86400000L).orElseThrow(()->new ProviderException("AUDIO_NOT_FOUND",404));}
  public Map<String,Object> status(){var config=settings.snapshot();Map<String,Object> s=new HashMap<>();s.put("provider",config.doubaoReady()?"doubao":"browser");s.put("requestedProvider",config.ttsProvider());s.put("configured",config.doubaoReady());s.put("resourceId",config.doubaoResourceId());s.put("voice",config.doubaoSpeaker());s.put("lastSuccessAt",lastSuccessAt);s.put("lastLatencyMs",lastLatencyMs);s.put("lastError",config.ttsFingerprint().equals(circuitScope)?lastError:"");s.put("circuitOpen",config.ttsFingerprint().equals(circuitScope)&&System.currentTimeMillis()<blockedUntil);if("doubao".equals(config.ttsProvider())&&!config.doubaoReady())s.put("configurationIssue",config.doubaoAppId().isBlank()&&config.doubaoApiKey().isBlank()?"DOUBAO_APP_ID_REQUIRED":"DOUBAO_CREDENTIALS_MISSING");return s;}
  static byte[] wav(byte[] pcm){ByteBuffer b=ByteBuffer.allocate(44+pcm.length).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+pcm.length).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);return b.array();}
}
