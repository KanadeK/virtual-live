package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual private receipt parser with an in-memory HTTP client; no sockets or real token files. */
class NativeSongFailureTest {
  @TempDir Path dir;
  static final class MemoryClient extends HttpClient {
    final String json;final List<HttpRequest> requests=new ArrayList<>();
    MemoryClient(String json){this.json=json;}
    public Optional<CookieHandler> cookieHandler(){return Optional.empty();}
    public Optional<Duration> connectTimeout(){return Optional.empty();}
    public Redirect followRedirects(){return Redirect.NEVER;}
    public Optional<ProxySelector> proxy(){return Optional.empty();}
    public SSLContext sslContext(){try{return SSLContext.getDefault();}catch(Exception e){throw new AssertionError(e);}}
    public SSLParameters sslParameters(){return new SSLParameters();}
    public Optional<Authenticator> authenticator(){return Optional.empty();}
    public Version version(){return Version.HTTP_1_1;}
    public Optional<Executor> executor(){return Optional.empty();}
    @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler){requests.add(request);return (HttpResponse<T>)new MemoryResponse(request,new ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){
      requests.add(r);var subscriber=h.apply(new HttpResponse.ResponseInfo(){
        public int statusCode(){return 202;}
        public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}
        public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
      });
      subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription(){public void request(long n){}public void cancel(){}});
      subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));subscriber.onComplete();
      return subscriber.getBody().toCompletableFuture().thenApply(body->new MemoryAsyncResponse<>(r,body));
    }
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new AssertionError("unexpected async I/O");}
  }
  record MemoryAsyncResponse<T>(HttpRequest request,T body) implements HttpResponse<T> {
    public int statusCode(){return 202;}
    public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}
    public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}
    public Optional<SSLSession> sslSession(){return Optional.empty();}
    public URI uri(){return request.uri();}
    public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
  }
  record MemoryResponse(HttpRequest request,InputStream body) implements HttpResponse<InputStream> {
    public int statusCode(){return 202;}
    public Optional<HttpResponse<InputStream>> previousResponse(){return Optional.empty();}
    public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}
    public Optional<SSLSession> sslSession(){return Optional.empty();}
    public URI uri(){return request.uri();}
    public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
  }
  ProviderException reject(String code)throws Exception {
    Path token=dir.resolve("fake-bridge-token.txt");Files.writeString(token,"x".repeat(48));var mapper=new ObjectMapper();
    var client=new MemoryClient(mapper.writeValueAsString(Map.of("stage","rejected","errorCode",code,"message","private remote text must not be surfaced")));
    var transport=new AwooNativeTransport("http://127.0.0.1:17630",token.toString(),mapper);
    var field=AwooNativeTransport.class.getDeclaredField("client");field.setAccessible(true);field.set(transport,client);
    var request=new NativeMusicTransport.Request("one","event",1000,"local-console","owner","source","小宇","张震岳","ds-console","fixture");
    var error=assertThrows(ProviderException.class,()->transport.prepare(request));
    assertEquals(1,client.requests.size());assertEquals("/v2/requests/prepare",client.requests.getFirst().uri().getPath());assertEquals(409,error.status);
    assertFalse(error.toString().contains("private remote text"));return error;
  }
  @Test void actualReceiptParserPreservesOnlyKnownSelectionCategories()throws Exception {
    for(var pair:Map.of("ambiguous_song","QQ_SONG_AMBIGUOUS","song_metadata_mismatch","QQ_SONG_METADATA_MISMATCH","qq_metadata_unavailable","QQ_SONG_METADATA_UNAVAILABLE","qq_metadata_unverified","QQ_SONG_METADATA_UNVERIFIED").entrySet()){
      var error=reject(pair.getKey());assertEquals(pair.getValue(),error.code);assertTrue(NativeSongFailure.message(error,"fallback").contains("没有加入队列"));
    }
  }
  @Test void unknownRemoteErrorIsNotUsedAsPublicCodeOrNotice()throws Exception {
    var error=reject("private-remote-code");assertEquals("QQ_NORMAL_REQUEST_REJECTED",error.code);assertEquals("fallback",NativeSongFailure.message(error,"fallback"));
  }
  @Test void normalControllerReturnsAnActionableMessageAndStableCode(){
    var response=new MusicController(null,null).unavailable(NativeSongFailure.rejected("ambiguous_song"));
    var body=(Map<?,?>)response.getBody();assertEquals("QQ_SONG_AMBIGUOUS",body.get("code"));assertTrue(String.valueOf(body.get("error")).contains("完整版本名"));assertFalse(String.valueOf(body.get("error")).contains("通信异常"));
  }
  @Test void reconnectFailureHasAFixedPublicCategory()throws Exception {
    var error=reject("qq_reconnect_not_ready");assertEquals("QQ_RECONNECT_NOT_READY",error.code);
    assertTrue(NativeSongFailure.definitive(error));assertTrue(NativeSongFailure.message(error,"fallback").contains("重连"));
  }
}
