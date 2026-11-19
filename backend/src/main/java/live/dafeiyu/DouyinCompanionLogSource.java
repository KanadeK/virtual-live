package live.dafeiyu;
import java.util.function.Consumer;
import live.dafeiyu.Protocol.LiveEvent;
/** Future adapter for documented, user-authorized local event export only. */
public final class DouyinCompanionLogSource implements LiveEventSource {
  public void start(Consumer<LiveEvent> receiver) { throw new UnsupportedOperationException("No supported Companion event export has been configured"); }
  public void stop() {} public boolean running() { return false; }
}
