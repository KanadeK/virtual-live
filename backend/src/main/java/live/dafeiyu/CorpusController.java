package live.dafeiyu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestController @RequestMapping("/api/corpus")
public final class CorpusController {
  record Create(@NotBlank String type,@NotBlank @Size(max=320) String text,Boolean enabled) {}
  record Edit(@NotBlank @Size(max=320) String text,Boolean enabled) {}
  private final CorpusService corpus;
  public CorpusController(CorpusService corpus){this.corpus=corpus;}
  @GetMapping public Object status(@RequestParam(required=false) String type){return corpus.status(type);}
  @PostMapping public Object create(@Valid @RequestBody Create body){return corpus.create(body.type(),body.text(),body.enabled()==null||body.enabled());}
  @PutMapping("/idle") public Object idle(@RequestBody CorpusService.Idle settings){return corpus.idle(settings);}
  @PutMapping("/{id}") public Object edit(@PathVariable String id,@Valid @RequestBody Edit body){return corpus.update(id,body.text(),body.enabled()==null||body.enabled());}
  @DeleteMapping("/{id}") public Object delete(@PathVariable String id){return corpus.delete(id);}
  @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class}) public ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e instanceof IllegalArgumentException?e.getMessage():"语料输入格式不正确"));}
}
