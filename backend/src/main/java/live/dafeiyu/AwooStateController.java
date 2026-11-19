package live.dafeiyu;

import com.fasterxml.jackson.databind.*;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Implements only the official schemaVersion=1 read API; never sends to a control/test route. */
@RestController @RequestMapping("/api/music/awoo")
public final class AwooStateController {
  private final String base;private final ObjectMapper json;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
  public AwooStateController(Environment env,ObjectMapper json){
    this.json=json;base=env.getProperty("music.awoo-state-url","").replaceAll("/+$","");
    if(!base.isBlank()){URI uri=URI.create(base);if(!"http".equals(uri.getScheme())||!Set.of("127.0.0.1","localhost","[::1]","::1").contains(uri.getHost())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Awoo read URL must be loopback HTTP");}
  }
  @GetMapping("/status") public Map<String,Object> status(){
    if(base.isBlank())return result(false,false,"未配置嗷呜只读接口；未执行点歌或播放控制",Map.of(),List.of());
    try{
      var response=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/state")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()!=200||response.body().length()>262144)throw new IllegalArgumentException();
      JsonNode data=json.readTree(response.body());if(data.path("schemaVersion").asInt()!=1)return result(true,false,"嗷呜协议版本不兼容；未执行控制",Map.of(),List.of());
      Map<String,Object> current=track(data.path("current"));List<Map<String,Object>> queue=new ArrayList<>();data.path("queue").forEach(n->{if(queue.size()<20)queue.add(track(n));});
      boolean connected=data.path("player").path("connected").asBoolean();
      return result(true,connected,connected?(current.isEmpty()?"嗷呜已连接，暂无曲目":"观察到嗷呜当前曲目；实际出声尚未确认"):"嗷呜播放器未连接，实际出声尚未确认",current,queue);
    }catch(Exception unavailable){return result(true,false,"嗷呜只读接口未连接；未执行点歌或播放控制",Map.of(),List.of());}
  }
  private static Map<String,Object> track(JsonNode node){if(node==null||node.isNull()||!node.isObject())return Map.of();return Map.of("songId",InputFilter.clean(node.path("id").asText(),120),"title",InputFilter.clean(node.path("title").asText(),120),"artist",InputFilter.clean(node.path("artist").asText(),120));}
  private static Map<String,Object> result(boolean configured,boolean connected,String notice,Map<String,Object> current,List<Map<String,Object>> queue){return Map.of("configured",configured,"connected",connected,"readOnly",true,"controlAvailable",false,"playbackConfirmed",false,"notice",notice,"current",current,"queue",queue);}
}

