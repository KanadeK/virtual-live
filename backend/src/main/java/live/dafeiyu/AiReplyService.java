package live.dafeiyu;
import live.dafeiyu.Protocol.LiveEvent;
import java.util.Map;
public interface AiReplyService {
  String reply(LiveEvent event);
  default AiDecision decide(LiveEvent event){return AiDecision.chat(reply(event));}
  default boolean needsRemote(LiveEvent event){return false;}
  default String provider(){return "mock";}
  default Map<String,Object> status(){return Map.of("provider",provider());}
}
