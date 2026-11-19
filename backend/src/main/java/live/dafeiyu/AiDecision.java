package live.dafeiyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/** A decision is data; only a validated music request may create a queue action. */
public record AiDecision(String intent,String query,String artist,String reply) {
  public static AiDecision chat(String text){return new AiDecision("chat","","",text);}
  public boolean requestsMusic(){return "music_request".equals(intent);}
  public AiDecision limitReply(int max){return reply.codePointCount(0,reply.length())<=max?this:new AiDecision(intent,query,artist,reply.substring(0,reply.offsetByCodePoints(0,max)));}
  public static AiDecision parse(ObjectMapper json,String body) {
    try {
      JsonNode node=json.readTree(body);
      if(node==null||!node.isObject()||node.size()!=5||!node.path("v").isIntegralNumber()||node.path("v").asInt()!=1)throw new IllegalArgumentException();
      var fields=node.fieldNames();while(fields.hasNext())if(!Set.of("v","intent","query","artist","reply").contains(fields.next()))throw new IllegalArgumentException();
      for(String key:ListKeys.TEXT)if(!node.path(key).isTextual())throw new IllegalArgumentException();
      String intent=node.path("intent").textValue(),query=bounded(node.path("query").textValue(),60),artist=bounded(node.path("artist").textValue(),30),reply=bounded(node.path("reply").textValue(),80);
      if(!Set.of("chat","music_request","clarify").contains(intent))throw new IllegalArgumentException();
      if("music_request".equals(intent)) {
        if(query.isBlank()||!reply.isBlank()||!InputFilter.safe(query+" "+artist)||(query+artist).matches("(?s).*(https?://|[\\r\\n]).*"))throw new IllegalArgumentException();
      } else if(!query.isEmpty()||!artist.isEmpty()||reply.isBlank()||!InputFilter.safe(reply))throw new IllegalArgumentException();
      return new AiDecision(intent,query,artist,reply);
    } catch(Exception invalid){throw new ProviderException("DEEPSEEK_INVALID_DECISION",502);}
  }
  private static String bounded(String value,int max) {
    if(value==null||value.codePointCount(0,value.length())>max||value.indexOf('\u0000')>=0)throw new IllegalArgumentException();
    return value.strip();
  }
  private static final class ListKeys {static final String[] TEXT={"intent","query","artist","reply"};}
}

