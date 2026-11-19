package live.dafeiyu;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.util.Map;

/** Only opaque, server-issued track tokens are accepted; upstream credentials stay server-side. */
@RestController @RequestMapping("/api/music")
public final class MusicSourceController {
  private final MusicCatalog catalog;
  public MusicSourceController(MusicCatalog catalog){this.catalog=catalog;}
  @GetMapping("/provider") public Object provider(){return catalog.providerStatus();}
  @GetMapping("/stream/{token}") public ResponseEntity<StreamingResponseBody> stream(@PathVariable String token,@RequestHeader(value="Range",required=false) String range)throws Exception {
    var upstream=catalog.openStream(token,range);var builder=ResponseEntity.status(upstream.statusCode()).header("Cache-Control","no-store");
    for(String name:new String[]{"Content-Type","Content-Length","Content-Range","Accept-Ranges"})upstream.headers().firstValue(name).ifPresent(value->builder.header(name,value));
    return builder.body(output->MusicStreamLimits.transfer(upstream.body(),output));
  }
  @ExceptionHandler(ProviderException.class) public ResponseEntity<?> unavailable(ProviderException e){return ResponseEntity.status(e.status).body(Map.of("error",e.code));}
  @ExceptionHandler(Exception.class) public ResponseEntity<?> failed(){return ResponseEntity.status(502).body(Map.of("error","MUSIC_STREAM_UNAVAILABLE"));}
}

