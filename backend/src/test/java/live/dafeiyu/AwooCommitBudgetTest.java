package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP fixtures, including a body arriving after its headers. */
class AwooCommitBudgetTest {
  @TempDir Path dir;
  final ObjectMapper json=new ObjectMapper();
  final NativeMusicTransport.Prepared prepared=new NativeMusicTransport.Prepared("one","safe-reservation","qq-one","小宇","张震岳");
  interface Handler {void handle(HttpExchange exchange)throws Exception;}
  final class Server implements AutoCloseable {
    final HttpServer server;final ExecutorService workers=Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
    final AtomicInteger posts=new AtomicInteger(),gets=new AtomicInteger();
    Server(Handler handler)throws Exception {
      server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
      server.createContext("/",exchange->{try{if(exchange.getRequestMethod().equals("POST"))posts.incrementAndGet();else gets.incrementAndGet();handler.handle(exchange);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Exception cancelled){/* Fixture peer may cancel a late body. */}finally{exchange.close();}});server.start();
    }
    AwooNativeTransport transport()throws Exception {Path token=dir.resolve("fixture-token");Files.writeString(token,"x".repeat(48));return new AwooNativeTransport("http://127.0.0.1:"+server.getAddress().getPort(),token.toString(),json);}
    public void close(){server.stop(0);workers.shutdownNow();}
  }
  void reply(HttpExchange exchange,Object data,long bodyDelayMs)throws Exception {
    byte[] bytes=json.writeValueAsBytes(data);exchange.sendResponseHeaders(200,bytes.length);
    // An early byte proves that merely receiving headers cannot end the deadline.
    exchange.getResponseBody().write(bytes,0,1);exchange.getResponseBody().flush();
    if(bodyDelayMs>0)Thread.sleep(bodyDelayMs);exchange.getResponseBody().write(bytes,1,bytes.length-1);
  }
  static long millis(long started){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);}

  @Test void acceptsTerminalQueuedAfterMoreThanTheOldTwoSeconds()throws Exception {
    AtomicInteger gets=new AtomicInteger();
    try(var server=new Server(e->{if(e.getRequestMethod().equals("POST"))reply(e,Map.of("stage","committing"),350);else {int n=gets.incrementAndGet();reply(e,Map.of("stage",n>=3?"queued":"committing"),n>=3?1100:1800);}})){
      var transport=server.transport();long start=System.nanoTime();transport.commit(prepared);long elapsed=millis(start);
      assertTrue(elapsed>=4800&&elapsed<6300,"elapsed="+elapsed);assertEquals(1,server.posts.get());assertEquals(3,gets.get());
    }
  }
  @Test void totalBudgetIncludesFirstPostAndTheLastPartialGetBody()throws Exception {
    AtomicInteger gets=new AtomicInteger();
    try(var server=new Server(e->{if(e.getRequestMethod().equals("POST"))reply(e,Map.of("stage","committing"),1700);else {int n=gets.incrementAndGet();reply(e,Map.of("stage",n>=3?"queued":"committing"),n==1?1900:n==2?1500:1800);}})){
      var transport=server.transport();long start=System.nanoTime();var error=assertThrows(ProviderException.class,()->transport.commit(prepared));long elapsed=millis(start);
      assertTrue(elapsed>=5700&&elapsed<6500,"elapsed="+elapsed);assertEquals("QQ_QUEUE_COMMIT_UNCONFIRMED",error.code);assertEquals(1,server.posts.get());assertTrue(gets.get()>=3);
    }
  }
  @Test void individualHttpDeadlineAlsoIncludesAStalledResponseBody()throws Exception {
    try(var server=new Server(e->reply(e,Map.of("stage","committing"),3500))){
      var transport=server.transport();long start=System.nanoTime();var error=assertThrows(ProviderException.class,()->transport.commit(prepared));long elapsed=millis(start);
      assertEquals("QQ_BRIDGE_UNAVAILABLE",error.code);assertTrue(elapsed>=2200&&elapsed<3100,"elapsed="+elapsed);
    }
  }
  @Test void sharedDeadlineCanBeShortenedByTimeAlreadySpentRenewingLease()throws Exception {
    try(var server=new Server(e->reply(e,Map.of("stage",e.getRequestMethod().equals("POST")?"committing":"queued"),e.getRequestMethod().equals("POST")?300:1500))){
      var transport=server.transport();long start=System.nanoTime();var error=assertThrows(ProviderException.class,()->transport.commit(prepared,start+TimeUnit.MILLISECONDS.toNanos(650)));long elapsed=millis(start);
      assertEquals("QQ_QUEUE_COMMIT_UNCONFIRMED",error.code);assertTrue(elapsed>=500&&elapsed<1100,"elapsed="+elapsed);
    }
  }
  @Test void rejectedReceiptRetainsOnlyItsFixedReconnectCategory()throws Exception {
    try(var server=new Server(e->reply(e,e.getRequestMethod().equals("POST")?Map.of("stage","committing"):Map.of("stage","rejected","errorCode","qq_reconnect_not_ready","message","private remote detail"),0))){
      var error=assertThrows(ProviderException.class,()->server.transport().commit(prepared));assertEquals("QQ_RECONNECT_NOT_READY",error.code);assertTrue(NativeSongFailure.message(error,"fallback").contains("重连"));assertFalse(error.toString().contains("private"));
    }
  }
  @Test void unknownRejectedCodeIsNormalizedInsteadOfExposed()throws Exception {
    try(var server=new Server(e->reply(e,Map.of("stage","rejected","errorCode","private-reconnect-detail"),0))){
      var error=assertThrows(ProviderException.class,()->server.transport().commit(prepared));assertEquals("QQ_NORMAL_REQUEST_REJECTED",error.code);assertFalse(error.toString().contains("private"));
    }
  }
  @Test void oversizedBodyStillFailsBeforeItCanBecomeAReceipt()throws Exception {
    try(var server=new Server(e->reply(e,Map.of("stage","queued","padding","z".repeat(32768)),0))){
      var error=assertThrows(ProviderException.class,()->server.transport().commit(prepared));assertEquals("QQ_BRIDGE_UNAVAILABLE",error.code);
    }
  }
  @Test void interruptCancelsThePendingExchangeAndPreservesInterruptFlag()throws Exception {
    CountDownLatch headers=new CountDownLatch(1);AtomicBoolean flag=new AtomicBoolean();
    try(var server=new Server(e->{byte[] bytes="{\"stage\":\"committing\"}".getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes,0,1);e.getResponseBody().flush();headers.countDown();Thread.sleep(10000);e.getResponseBody().write(bytes,1,bytes.length-1);})){
      var transport=server.transport();var work=new FutureTask<>(()->{try{transport.commit(prepared);throw new AssertionError("Expected interruption");}catch(ProviderException error){flag.set(Thread.currentThread().isInterrupted());return error.code;}});Thread thread=Thread.ofPlatform().daemon().start(work);
      assertTrue(headers.await(2,TimeUnit.SECONDS));thread.interrupt();assertEquals("QQ_BRIDGE_INTERRUPTED",work.get(1,TimeUnit.SECONDS));assertTrue(flag.get());
    }
  }
}
