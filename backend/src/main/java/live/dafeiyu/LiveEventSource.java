package live.dafeiyu;
import java.util.function.Consumer;
import live.dafeiyu.Protocol.LiveEvent;
public interface LiveEventSource { void start(Consumer<LiveEvent> receiver); void stop(); boolean running(); }
