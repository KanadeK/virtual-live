package live.dafeiyu;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AwooNativeTransportTest {
  @TempDir Path temp;final ObjectMapper json=new ObjectMapper();
  String tokenFile()throws Exception{Path p=temp.resolve("fixture-token");Files.writeString(p,"x".repeat(48));return p.toString();}
  @Test void serializesPrivateV2NormalRequestWithoutBrowserOrElevatedRoles()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);List<String> paths=new ArrayList<>();AtomicReference<JsonNode> request=new AtomicReference<>();AtomicBoolean auth=new AtomicBoolean(true);
    server.createContext("/",e->{try{String path=e.getRequestURI().getPath();paths.add(path);auth.compareAndSet(true,("Bearer "+"x".repeat(48)).equals(e.getRequestHeaders().getFirst("Authorization"))&&e.getRequestHeaders().getFirst("Origin")==null);byte[] input=e.getRequestBody().readAllBytes();JsonNode body=input.length==0?json.createObjectNode():json.readTree(input);Object response;
      if(path.equals("/v1/context"))response=Map.of("active",body.path("active").asBoolean(),"revision",body.path("revision").asLong());
      else if(path.equals("/v2/requests/prepare")){request.set(body);response=Map.of("stage","prepared","reservation","safe-reservation","track",Map.of("id","qq-one","title","小宇","artist","张震岳"));}
      else if(path.equals("/v2/requests/one"))response=Map.of("stage","queued");
      else if(path.equals("/v2/state"))response=Map.of("paused",true,"state",Map.of("nativeEvidence",Map.of("pauseConfirmed",true,"playbackConfirmed",true,"completeSongVerified",true,"reportedPosition",2),"snapshot",Map.of("current",Map.of("id","qq-one"))));
      else response=Map.of("accepted",true);
      byte[] result=json.writeValueAsBytes(response);e.sendResponseHeaders(200,result.length);e.getResponseBody().write(result);
    }finally{e.close();}});server.start();try{
      var transport=new AwooNativeTransport("http://127.0.0.1:"+server.getAddress().getPort(),tokenFile(),json);transport.context(1,"owner","capture",System.currentTimeMillis()+9000,true);var prepared=transport.prepare(new NativeMusicTransport.Request("one","event",System.currentTimeMillis(),"bilibili","owner","capture","小宇","张震岳","9002","观众"));transport.commit(prepared);transport.speech(1,"owner","capture","speech",true,System.currentTimeMillis()+8000);transport.pause(1,"owner","capture",true);var observation=transport.observe();
      assertTrue(auth.get());assertEquals(0,request.get().path("viewer").path("guardLevel").asInt());assertFalse(request.get().path("viewer").path("isManager").asBoolean());assertEquals("9002",request.get().path("viewer").path("uid").asText());assertTrue(paths.contains("/v2/requests/one/commit"));assertFalse(paths.stream().anyMatch(p->p.contains("test/request-song")||p.contains("5556")));assertTrue(observation.playbackConfirmed());assertFalse(observation.completeSongVerified());
    }finally{server.stop(0);}
  }
  @Test void rejectsNonLoopbackUserinfoPathsAndRemoteHostBeforeMakingRequests()throws Exception {
    String token=tokenFile();for(String url:List.of("http://localhost:17630","https://127.0.0.1:17630","http://example.com:17630","http://user@127.0.0.1:17630","http://127.0.0.1:17630/path","http://127.0.0.1:17630/?token=x"))assertThrows(IllegalArgumentException.class,()->new AwooNativeTransport(url,token,json));
  }
  @Test void remoteErrorBodiesAndTokenFileValuesAreNeverReturnedToProduct()throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{try{byte[] body="private-remote-error".getBytes();e.sendResponseHeaders(500,body.length);e.getResponseBody().write(body);}finally{e.close();}});server.start();try{var transport=new AwooNativeTransport("http://127.0.0.1:"+server.getAddress().getPort(),tokenFile(),json);var error=assertThrows(ProviderException.class,transport::observe);assertEquals("QQ_BRIDGE_UNAVAILABLE",error.code);assertFalse(error.toString().contains("private-remote-error"));}finally{server.stop(0);}
  }
}
