package live.dafeiyu;
import jakarta.validation.constraints.*;
import java.util.*;
public final class Protocol {
  private Protocol() {}
  public enum EventType { USER_ENTER, COMMENT, GIFT, LIKE, SYSTEM }
  public record LiveEvent(@Size(max=100) String eventId, @NotNull EventType type,
      @Size(max=80) String userId, @Size(max=40) String nickname, @Size(max=500) String content,
      @Size(max=40) String giftName, @Min(0) @Max(9999) int giftCount, long timestamp) {}
  public record ManualSpeech(@NotBlank @Size(max=160) String text) {}
  public record ClientId(@NotBlank @Size(max=100) String clientId) {}
  public record TtsRequest(@NotBlank String taskId,@NotBlank String clientId,@DecimalMin("0.5") @DecimalMax("1.8") double rate,@DecimalMin("0.5") @DecimalMax("2.0") double pitch) {}
  public record Completion(@NotBlank String taskId, @NotBlank String clientId) {}
  public record Action(@Pattern(regexp="idle|walk|walk_left|walk_right|talk|blink|wave|happy|surprised|celebrate|sad|think") String action,
      @Pattern(regexp="neutral|happy|surprised|sad|think") String emotion) {}
  public record AutoSettings(@Min(5) @Max(120) int minSeconds, @Min(5) @Max(120) int maxSeconds) {}
  public record AudioSettings(boolean enabled, @DecimalMin("0.5") @DecimalMax("1.8") double rate,
      @DecimalMin("0.5") @DecimalMax("2.0") double pitch,
      @Pattern(regexp="[a-zA-Z0-9_-]{0,64}") String voice) {}
  public record SpeechTask(String taskId, String text, String emotion, String action, int priority,
      long createdAt, LiveEvent event) {}
  public record LogEntry(String id, long timestamp, String type, String text) {}
}
