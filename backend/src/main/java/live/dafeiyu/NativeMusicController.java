package live.dafeiyu;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;

@RestController @RequestMapping("/api/music/native")
public final class NativeMusicController {
  private final NativeMusicBridge bridge;
  public NativeMusicController(NativeMusicBridge bridge){this.bridge=bridge;}
  record Stopped(@NotBlank String clientId,@NotBlank String ownerEpoch,@NotBlank String stopNonce,boolean stopped){}
  record Speech(@NotBlank String clientId,@NotBlank String ownerEpoch,@NotBlank String executionId,@NotBlank String taskId,@Pattern(regexp="[A-Za-z0-9:_-]{1,120}") @NotBlank String playbackId,@Min(1) long sequence,@Pattern(regexp="START|PULSE|END") @NotBlank String phase){}
  @GetMapping("/status") public Object status(){return bridge.status();}
  @PostMapping("/html-stopped") public Object stopped(@Valid @RequestBody Stopped body){return Map.of("accepted",bridge.htmlStopped(body.clientId,body.ownerEpoch,body.stopNonce,body.stopped));}
  @PostMapping("/speech") public Object speech(@Valid @RequestBody Speech body){return Map.of("accepted",bridge.speech(body.clientId,body.ownerEpoch,body.executionId,body.taskId,body.playbackId,body.sequence,body.phase));}
  @PostMapping("/release") public Object release(){bridge.release();return bridge.status();}
  @ExceptionHandler(ProviderException.class) public ResponseEntity<?> unavailable(ProviderException error){return ResponseEntity.status(error.status).body(Map.of("error",error.code));}
}
