package live.dafeiyu;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MusicTest {
  @TempDir Path dir;
  LiveRoom room;MusicService music;AtomicInteger searches;
  @BeforeEach void setup(){room=new LiveRoom(new MockAiReplyService());searches=new AtomicInteger();music=new MusicService(q->{searches.incrementAndGet();return song(q);},room);music.repeat(false);}
  @AfterEach void cleanup(){room.shutdown();}
  MusicCatalog.Song song(String id){return new MusicCatalog.Song(id,"歌曲 "+id,"歌手","https://music.163.com/#/song?id="+id,"https://music.163.com/song/media/outer/url?id="+id+".mp3",3000);}
  @SuppressWarnings("unchecked") List<MusicService.Entry> list(){return (List<MusicService.Entry>)music.status().get("playlist");}
  @Test void fifoAndOnlyLiveOwnerCanAdvance(){room.subscribe("live",true);room.subscribe("observer",true);room.subscribe("control",false);music.request("点歌 1");music.request("点歌 2");String id=list().getFirst().entryId();assertFalse(music.completed(id,"control",false));assertFalse(music.completed(id,"observer",false));assertTrue(music.completed(id,"live",false));assertFalse(music.completed(id,"live",false));assertEquals("2",list().getFirst().song().songId());}
  @Test void limitCountsCurrentAndDoesNotSearchWhenFull(){for(int i=1;i<=20;i++)music.request("点歌 "+i);assertEquals(20,list().size());assertThrows(IllegalArgumentException.class,()->music.request("点歌 21"));assertEquals(20,searches.get());music.skip();music.request("点歌 21");assertEquals(20,list().size());assertEquals("2",list().getFirst().song().songId());}
  @Test void duplicateValidationPauseRemoveAndClear(){assertThrows(IllegalArgumentException.class,()->music.request("稻香"));assertThrows(IllegalArgumentException.class,()->music.request("点歌   "));music.request("点歌 1");assertThrows(IllegalArgumentException.class,()->music.request("点歌 1"));room.subscribe("live",true);String id=list().getFirst().entryId();music.pause(true);assertFalse(music.completed(id,"live",false));assertEquals(1,list().size());music.pause(false);music.remove(id);assertTrue(list().isEmpty());music.request("点歌 2");music.clear();assertTrue(list().isEmpty());assertThrows(IllegalArgumentException.class,()->music.remove("missing"));}
  @Test void failedPlaybackAdvancesAndProgressIsOwned(){room.subscribe("live",true);music.request("点歌 1");music.request("点歌 2");String id=list().getFirst().entryId();assertFalse(music.progress(id,"other",15));assertTrue(music.progress(id,"live",15));assertEquals(15.0,music.status().get("position"));assertTrue(music.completed(id,"live",true));assertEquals(0.0,music.status().get("position"));assertTrue(music.status().get("notice").toString().contains("无法播放"));assertEquals("2",list().getFirst().song().songId());}
  @Test void concurrentSearchCannotOverflowQueue()throws Exception{CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1);music=new MusicService(q->{if(q.equals("lastA")||q.equals("lastB")){started.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}return song(q);},room);for(int i=0;i<19;i++)music.request("点歌 "+i);try(var threads=Executors.newVirtualThreadPerTaskExecutor()){var a=threads.submit(()->{try{music.request("点歌 lastA");return true;}catch(IllegalArgumentException e){return false;}});var b=threads.submit(()->{try{music.request("点歌 lastB");return true;}catch(IllegalArgumentException e){return false;}});assertTrue(started.await(1,TimeUnit.SECONDS));assertEquals(19,list().size());release.countDown();assertNotEquals(a.get(),b.get());assertEquals(20,list().size());}finally{release.countDown();}}
  @Test void loopingRetainsSongsAndRejectsOldAcknowledgements(){
    room.subscribe("live",true);music.repeat(true);music.request("点歌 1");music.request("点歌 2");
    String first=list().getFirst().entryId();music.progress(first,"live",15);
    assertTrue(music.completed(first,"live",false));assertEquals("2",list().getFirst().song().songId());
    assertEquals("1",list().getLast().song().songId());assertNotEquals(first,list().getLast().entryId());
    assertFalse(music.completed(first,"live",false));assertFalse(music.progress(first,"live",20));assertEquals(0.0,music.status().get("position"));
    music.skip();assertEquals("1",list().getFirst().song().songId());assertEquals(2,list().size());
  }
  @Test void singleSongLoopsWithFreshIdAndFailuresDoNotLoop(){
    room.subscribe("live",true);music.repeat(true);music.request("点歌 1");String first=list().getFirst().entryId();
    assertTrue(music.completed(first,"live",false));assertEquals(1,list().size());assertNotEquals(first,list().getFirst().entryId());
    assertFalse(music.completed(first,"live",false));
    assertTrue(music.completed(list().getFirst().entryId(),"live",true));assertTrue(list().isEmpty());
  }
  @Test void addingDefaultsPreservesCurrentPlaybackAndRespectsCapacity(){
    music.request("点歌 1");String current=list().getFirst().entryId();music.pause(true);
    music.addDefaults(List.of(song("1"),song("2"),song("2")));assertEquals(2,list().size());assertEquals(current,list().getFirst().entryId());
    assertEquals(true,music.status().get("paused"));assertEquals(true,music.status().get("repeat"));
    music.addDefaults(List.of(song("1"),song("2")));assertEquals(2,list().size());
    for(int i=3;i<=19;i++)music.request("点歌 "+i);
    music.addDefaults(List.of(song("20"),song("21")));assertEquals(20,list().size());assertEquals("20",list().getLast().song().songId());
  }
  @Test void localDefaultsLoadWithoutNetworkAndManualListFallsBack()throws Exception{
    Files.write(dir.resolve("background.wav"),DoubaoTtsService.wav(new byte[960]));Files.writeString(dir.resolve("private.txt"),"not music");
    var defaults=new DefaultMusicPlaylist(music,new ObjectMapper(),new MockEnvironment().withProperty("music.local-dir",dir.toString()));
    defaults.initialize();assertTrue(list().isEmpty());assertEquals(0,searches.get());assertEquals("local",music.status().get("source"));
    var bg=(MusicService.Entry)music.status().get("current");assertEquals("local",bg.source());assertEquals("",bg.song().songUrl());
    String localId=bg.song().audioUrl().replace("/api/music/local/","");assertEquals(1004,defaults.audio(localId).contentLength());assertThrows(ProviderException.class,()->defaults.audio("../../private.txt"));
    room.subscribe("live",true);assertTrue(music.progress(bg.entryId(),"live",.1));music.request("点歌 1");assertEquals("netease",music.status().get("source"));
    assertFalse(music.completed(bg.entryId(),"live",false));assertTrue(music.completed(list().getFirst().entryId(),"live",false));assertEquals("local",music.status().get("source"));
    assertEquals(0.0,music.status().get("position"));assertNotEquals(bg.entryId(),((MusicService.Entry)music.status().get("current")).entryId());
    music.request("点歌 2");music.clear();assertTrue(list().isEmpty());assertEquals(true,music.status().get("fallbackActive"));
  }
  @Test void localLoopPauseAndProgressRetainSingleOwnerAndRejectStaleIds(){
    music.localBackground(List.of(new MusicCatalog.Song("local-1","背景","本地","","/api/music/local/1",1000,"local")));music.repeat(true);room.subscribe("live",true);room.subscribe("observer",true);
    String first=((MusicService.Entry)music.status().get("current")).entryId();assertFalse(music.completed(first,"observer",false));
    music.pause(true);assertFalse(music.completed(first,"live",false));music.pause(false);assertTrue(music.completed(first,"live",false));
    String next=((MusicService.Entry)music.status().get("current")).entryId();assertNotEquals(first,next);assertFalse(music.completed(first,"live",false));assertFalse(music.progress(first,"live",.5));
    assertTrue(music.progress(next,"live",.5));assertEquals(.5,music.status().get("position"));music.skip();assertEquals(0.0,music.status().get("position"));
    music.repeat(false);assertTrue(music.completed(((MusicService.Entry)music.status().get("current")).entryId(),"live",false));assertEquals("none",music.status().get("source"));music.skip();assertEquals("local",music.status().get("source"));
  }
  @Test void publicSearchPrefersExactTitleAndCachesWithoutCookies()throws Exception{
    HttpServer fixture=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger calls=new AtomicInteger();
    fixture.createContext("/search",e->{try{calls.incrementAndGet();assertNull(e.getRequestHeaders().getFirst("Cookie"));String form=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertTrue(form.contains("type=1"));assertTrue(form.contains(URLEncoder.encode("晴天",StandardCharsets.UTF_8)));byte[] response="{\"result\":{\"songs\":[{\"id\":1,\"name\":\"晴天翻唱\",\"artists\":[]},{\"id\":2,\"name\":\"晴天\",\"duration\":3000,\"artists\":[{\"name\":\"歌手\"}]}]}}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);}finally{e.close();}});fixture.start();
    try{MockEnvironment env=new MockEnvironment().withProperty("music.search-url","http://127.0.0.1:"+fixture.getAddress().getPort()+"/search");var catalog=new NeteaseMusicCatalog(new IntegrationConfig(env),new ObjectMapper(),env);var song=catalog.find("晴天");assertEquals("2",song.songId());assertEquals("歌手",song.artist());assertEquals("https://music.163.com/#/song?id=2",song.songUrl());assertEquals(song,catalog.find("晴天"));assertEquals(1,calls.get());}finally{fixture.stop(0);}
  }
}
