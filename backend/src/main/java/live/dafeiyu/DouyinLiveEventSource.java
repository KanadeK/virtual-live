package live.dafeiyu;
import java.util.function.Consumer;
import live.dafeiyu.Protocol.LiveEvent;
/** Integration boundary for future officially authorized event feeds. */
public final class DouyinLiveEventSource implements LiveEventSource {
  public void start(Consumer<LiveEvent> receiver) { throw new UnsupportedOperationException("Officially authorized Douyin integration is not configured"); }
  public void stop() {} public boolean running() { return false; }
}
