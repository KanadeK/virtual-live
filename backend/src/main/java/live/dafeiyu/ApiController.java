package live.dafeiyu;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.validation.Valid;
import java.util.*;
import live.dafeiyu.Protocol.*;
@RestController @RequestMapping("/api")
public class ApiController {
  private final LiveRoom room;private final MockLiveEventSource source;
  public ApiController(LiveRoom room,MockLiveEventSource source){this.room=room;this.source=source;}
  @GetMapping("/health") public Object health(){return Map.of("status","ok","application","小助手 AI 直播间","java",Runtime.version().feature());}
  @PostMapping("/demo/event") public Object event(@Valid @RequestBody LiveEvent e){return room.accept(e);}
  @PostMapping("/demo/auto/start") public Object start(@Valid @RequestBody(required=false) AutoSettings settings){if(settings!=null){if(settings.maxSeconds()<settings.minSeconds())throw new IllegalArgumentException("最大间隔不能小于最小间隔");source.configure(settings.minSeconds(),settings.maxSeconds());}source.start(room::accept);return Map.of("running",true);}
  @PostMapping("/demo/auto/stop") public Object stop(){source.stop();return Map.of("running",false);}
  @PostMapping("/character/action") public Object action(@Valid @RequestBody Action a){room.action(a);return Map.of("ok",true);}
  @PostMapping("/speech/manual") public Object manual(@Valid @RequestBody ManualSpeech m){room.manual(m.text());return Map.of("ok",true);}
  @PostMapping("/speech/completed") public Object completed(@Valid @RequestBody Completion c){return Map.of("accepted",room.complete(c.taskId(),c.clientId()));}
  @PostMapping("/speech/clear") public Object clear(){room.clear();return Map.of("ok",true);}
  @PostMapping("/audio/settings") public Object audio(@Valid @RequestBody AudioSettings s){room.audio(s);return Map.of("ok",true);}
  @PostMapping("/client/heartbeat") public Object heartbeat(@Valid @RequestBody ClientId c){return Map.of("connected",room.heartbeat(c.clientId()));}
  @PostMapping("/client/disconnect") public Object disconnect(@Valid @RequestBody ClientId c){room.release(c.clientId());return Map.of("ok",true);}
  @GetMapping("/status") public Object status(){Map<String,Object> s=new HashMap<>(room.status());s.put("autoRunning",source.running());return s;}
  @GetMapping(value="/events/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream(@RequestParam String clientId,@RequestParam(defaultValue="preview") String role){if(clientId.length()>100)throw new IllegalArgumentException("clientId too long");return room.subscribe(clientId,"live".equals(role));}
  @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class}) public ResponseEntity<?> bad(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e instanceof MethodArgumentNotValidException?"输入格式不正确，请检查范围或必填项":e.getMessage()));}
}
