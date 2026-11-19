package live.dafeiyu;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;

@RestController @RequestMapping("/api/music")
public final class MusicController {
  record Request(@NotBlank @Size(max=140) String command,@Size(max=30) String artist) {}
  record Pause(boolean paused) {}
  record Repeat(boolean enabled) {}
  record Volume(@DecimalMin("0") @DecimalMax("1") double volume) {}
  record Removal(@NotBlank String entryId) {}
  record Preparation(@NotBlank String entryId,@NotBlank String clientId,@NotBlank String ownerEpoch) {}
  record Playback(@NotBlank String entryId,@NotBlank String clientId,@NotBlank String ownerEpoch,boolean failed,@DecimalMin("0") @DecimalMax("86400") double seconds) {}
  record PlaybackState(@NotBlank String entryId,@NotBlank String clientId,@NotBlank String ownerEpoch,@NotBlank @Pattern(regexp="LOADING|PLAYING|PAUSED") String phase,@DecimalMin("0") @DecimalMax("86400") double seconds) {}
  private final MusicService music;
  private final DefaultMusicPlaylist defaults;
  private NativeMusicBridge nativeBridge;
  @org.springframework.beans.factory.annotation.Autowired void nativeBridge(NativeMusicBridge bridge){nativeBridge=bridge;}
  private boolean nativeEnabled(){return nativeBridge!=null&&nativeBridge.enabled();}
  private void htmlControl(){if(nativeEnabled()&&nativeBridge.blocksHtml())throw new ProviderException("QQ_NATIVE_CONTROL_UNAVAILABLE",409);}
  public MusicController(MusicService music,DefaultMusicPlaylist defaults){this.music=music;this.defaults=defaults;}
  @GetMapping("/status") public Object status(){return music.status();}
  @PostMapping("/request") public Object request(@Valid @RequestBody Request body){if(!nativeEnabled())return music.request(body.command);var result=nativeBridge.consoleRequest(body.command,body.artist);music.requestNotice(String.valueOf(result.get("notice")));return music.status();}
  @PostMapping("/pause") public Object pause(@RequestBody Pause body){if(!nativeEnabled()||!nativeBridge.blocksHtml())return music.pause(body.paused);nativeBridge.pause(body.paused);return music.status();}
  @PostMapping("/repeat") public Object repeat(@RequestBody Repeat body){htmlControl();return music.repeat(body.enabled);}
  @PostMapping("/defaults") public Object defaults(){if(nativeEnabled())nativeBridge.release();return defaults.add();}
  @GetMapping("/local/{id}") public ResponseEntity<org.springframework.core.io.Resource> local(@PathVariable String id){var file=defaults.audio(id);return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(DefaultMusicPlaylist.mediaType(file))).header("Cache-Control","private, max-age=3600").body(file);}
  @ExceptionHandler(ProviderException.class) public ResponseEntity<?> unavailable(ProviderException error){return ResponseEntity.status(error.status).body(Map.of("error",NativeSongFailure.message(error,error.code),"code",error.code));}
  @PostMapping("/volume") public Object volume(@Valid @RequestBody Volume body){htmlControl();return music.volume(body.volume);}
  @PostMapping("/skip") public Object skip(){htmlControl();return music.skip();}
  @PostMapping("/remove") public Object remove(@Valid @RequestBody Removal body){htmlControl();return music.remove(body.entryId);}
  @PostMapping("/clear") public Object clear(){if(nativeEnabled())nativeBridge.release();return music.clear();}
  @PostMapping("/completed") public Object completed(@Valid @RequestBody Playback body){return Map.of("accepted",music.completed(body.entryId,body.clientId,body.ownerEpoch,body.failed));}
  @PostMapping("/progress") public Object progress(@Valid @RequestBody Playback body){return Map.of("accepted",music.progress(body.entryId,body.clientId,body.ownerEpoch,body.seconds));}
  @PostMapping("/prepare") public Object prepare(@Valid @RequestBody Preparation body){return music.prepare(body.entryId,body.clientId,body.ownerEpoch);}
  @PostMapping("/playback") public Object playback(@Valid @RequestBody PlaybackState body){return Map.of("accepted",music.playback(body.entryId,body.clientId,body.ownerEpoch,body.phase,body.seconds));}
  @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class}) public ResponseEntity<?> bad(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e instanceof MethodArgumentNotValidException?"点歌输入格式不正确":e.getMessage()));}
}
