package live.dafeiyu;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MusicOwnerEpochTest {
  LiveRoom room;MusicService music;
  @BeforeEach void setup(){room=new LiveRoom(new MockAiReplyService());music=new MusicService(q->new MusicCatalog.Song(q,q,"验收","","/fixture.wav",1000,"local"),room);}
  @AfterEach void close(){room.shutdown();}
  @SuppressWarnings("unchecked") String current(){return ((MusicService.Entry)((List<?>)music.status().get("playlist")).getFirst()).entryId();}
  @Test void sameClientReconnectRejectsOldPlayingProgressAndCompletion(){
    room.subscribe("a",true);music.request("点歌 一");String id=current(),old=room.ownerEpoch("a");
    assertTrue(music.playback(id,"a",old,"PLAYING",1));room.subscribe("a",true);String fresh=room.ownerEpoch("a");assertNotEquals(old,fresh);
    assertEquals(false,((Map<?,?>)music.status().get("playback")).get("confirmed"));
    assertFalse(music.playback(id,"a",old,"PLAYING",10));assertFalse(music.progress(id,"a",old,10));assertFalse(music.completed(id,"a",old,false));assertEquals(id,current());
    assertTrue(music.playback(id,"a",fresh,"PLAYING",2));assertTrue(music.progress(id,"a",fresh,3));
  }
  @Test void aToBToAAndTrackChangesDoNotReviveOldReceipts(){
    room.subscribe("a",true);room.subscribe("b",true);music.request("点歌 一");music.request("点歌 二");String first=current(),a1=room.ownerEpoch("a");
    room.release("a");String b=room.ownerEpoch("b");assertFalse(b.isBlank());assertFalse(music.progress(first,"a",a1,9));
    room.subscribe("a",true);room.release("b");String a2=room.ownerEpoch("a");assertNotEquals(a1,a2);assertFalse(music.playback(first,"a",a1,"PLAYING",8));assertFalse(music.completed(first,"b",b,false));
    music.skip();assertFalse(music.playback(first,"a",a2,"PLAYING",10));assertFalse(music.progress(first,"a",a2,10));assertFalse(music.completed(first,"a",a2,false));assertTrue(music.playback(current(),"a",a2,"PLAYING",0));
  }
  @Test void privateAndMetadataTargetsAreDeniedAndFixtureExceptionIsNarrow(){
    var env=new MockEnvironment().withProperty("music.audio-hosts","169.254.169.254,10.1.2.3,100.100.100.200,[fc00::1]");var policy=new AudioTargetPolicy(env,"http://127.0.0.1:17433");
    for(String url:List.of("https://169.254.169.254/","https://10.1.2.3/","https://100.100.100.200/","https://[fc00::1]/","http://127.0.0.1:17433/audio","file:///tmp/music.wav","https://example.invalid/audio"))assertThrows(IllegalArgumentException.class,()->policy.validate(URI.create(url)));
    var fixture=new AudioTargetPolicy(env.withProperty("music.allow-loopback-fixture","true"),"http://127.0.0.1:17433");fixture.validate(URI.create("http://127.0.0.1:17433/audio"));assertThrows(IllegalArgumentException.class,()->fixture.validate(URI.create("http://127.0.0.1:8080/api/config")));
  }
}

