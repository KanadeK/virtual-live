package live.dafeiyu;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import live.dafeiyu.Protocol.*;

@Service @Primary
public final class ConfiguredAiReplyService implements AiReplyService {
  private final IntegrationConfig config;private final MockAiReplyService mock;private final DeepSeekAiReplyService deepseek;private final UsageLedger usage;private volatile String lastFallback="";
  public ConfiguredAiReplyService(IntegrationConfig config,ObjectMapper json,UsageLedger usage,ProviderStore cache,MockAiReplyService mock){this.config=config;this.mock=mock;this.usage=usage;this.deepseek=new DeepSeekAiReplyService(config,json,usage,cache);}
  private boolean needsRemote(LiveEvent e,IntegrationConfig.Snapshot current){return current.deepseekReady()&&e.type()==EventType.COMMENT&&InputFilter.safe(e.content())&&(!IntegrationConfig.automatic(e)||current.paidAutoEnabled());}
  public boolean needsRemote(LiveEvent e){return needsRemote(e,config.snapshot());}
  public String reply(LiveEvent e){var current=config.snapshot();if(!needsRemote(e,current))return mock.reply(e);try{String text=deepseek.reply(e,current);lastFallback="";return text;}catch(ProviderException failure){lastFallback=failure.code;usage.add("aiFallbacks",1);return mock.reply(e);}}
  public AiDecision decide(LiveEvent e){var current=config.snapshot();if(!needsRemote(e,current))return AiDecision.chat(mock.reply(e));try{AiDecision value=deepseek.decide(e,current);lastFallback="";return value;}catch(ProviderException failure){lastFallback=failure.code;usage.add("aiFallbacks",1);return AiDecision.chat("主播这次没听清，先不改点歌队列，请再说一次。");}}
  public String provider(){return config.deepseekReady()?"deepseek":"mock";}
  public Map<String,Object> status(){Map<String,Object> result=new HashMap<>(deepseek.status());result.put("provider",provider());result.put("configured",config.deepseekReady());result.put("lastFallback",lastFallback);return result;}
}
