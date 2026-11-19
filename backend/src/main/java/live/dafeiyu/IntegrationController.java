package live.dafeiyu;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import live.dafeiyu.Protocol.*;

@RestController @RequestMapping("/api")
public final class IntegrationController {
  private final LiveRoom room;private final DoubaoTtsService tts;private final AiReplyService ai;private final IntegrationConfig config;private final UsageLedger usage;
  public IntegrationController(LiveRoom room,DoubaoTtsService tts,AiReplyService ai,IntegrationConfig config,UsageLedger usage){this.room=room;this.tts=tts;this.ai=ai;this.config=config;this.usage=usage;}
  @GetMapping("/integrations/status") public Object status(){return Map.of("ai",ai.status(),"tts",tts.status(),"usage",usage.status(),"paidAutoEnabled",config.snapshot().paidAutoEnabled());}
  @GetMapping("/integrations/config") public Object configuration(){return config.status();}
  @PutMapping("/integrations/config") public Object configure(@RequestBody com.fasterxml.jackson.databind.JsonNode patch){return config.update(patch);}
  @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException error){return ResponseEntity.badRequest().body(Map.of("error","云端配置字段或范围不正确"));}
  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) public ResponseEntity<?> unreadable(){return ResponseEntity.badRequest().body(Map.of("error","请求格式不正确"));}
  @PostMapping("/tts/synthesize") public Object synthesize(@Valid @RequestBody TtsRequest req){
    LiveRoom.SpeechAuthorization speech=room.authorizeSpeech(req.taskId(),req.clientId());
    if(!speech.enabled())return Map.of("provider","silent","reason","AUDIO_DISABLED");
    Map<String,Object> result=tts.synthesize(speech.text(),req.rate(),req.pitch(),speech.voice(),speech.automatic());
    room.audioPrepared(req.taskId(),req.clientId(),result.get("durationSeconds") instanceof Number n?n.doubleValue():0);
    return result;
  }
  @GetMapping("/tts/audio/{id}") public ResponseEntity<byte[]> audio(@PathVariable String id){return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).header("Cache-Control","private, max-age=3600").body(tts.audio(id));}
  @ExceptionHandler(ProviderException.class) public ResponseEntity<?> error(ProviderException error){return ResponseEntity.status(error.status>=400&&error.status<600?error.status:503).body(Map.of("error",error.code));}
}
