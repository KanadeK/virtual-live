package live.dafeiyu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/douyin")
public class DouyinCaptureController {
  public record Connect(@NotBlank @Size(max=300) String roomId, @Size(max=16000) String cookie) {}
  private final DouyinCaptureService capture;
  private final CaptureSwitch sources;
  public DouyinCaptureController(DouyinCaptureService capture, CaptureSwitch sources) { this.capture = capture; this.sources = sources; }
  @GetMapping("/status") public Object status() { return capture.snapshot(); }
  @PostMapping("/connect") public Object connect(@Valid @RequestBody Connect config) { return sources.connect("douyin", config.roomId(), config.cookie()); }
  @PostMapping("/disconnect") public Object disconnect() { return sources.disconnect("douyin"); }
  @PostMapping("/login") public Object login(@Valid @RequestBody Connect config) { return sources.login("douyin", config.roomId()); }
  @PostMapping("/login/cancel") public Object cancelLogin() { return sources.cancelLogin("douyin"); }
  @PostMapping("/clear") public Object clear() { return capture.clear(); }
  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  public ResponseEntity<?> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e instanceof MethodArgumentNotValidException ? "请输入有效的房间号和单行 Cookie" : e.getMessage())); }
}
