package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import live.dafeiyu.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorpusTest {
  @TempDir Path dir;
  ObjectMapper json=new ObjectMapper();CorpusService corpus;LiveRoom room;AtomicLong now;
  CorpusService fresh(){return new CorpusService(new IntegrationConfig(new MockEnvironment().withProperty("integration.data-dir",dir.toString())),json);}
  @BeforeEach void setup(){corpus=fresh();now=new AtomicLong(100000);room=new LiveRoom(mock(AiReplyService.class),now::get);room.corpus(corpus);}
  @AfterEach void stop(){room.shutdown();}
  @SuppressWarnings("unchecked") List<CorpusService.Item> items(){return (List<CorpusService.Item>)corpus.status(null).get("items");}
  @SuppressWarnings("unchecked") Map<String,Object> current(){return (Map<String,Object>)room.status().get("current");}
  void only(String type,String text){items().stream().filter(i->i.type().equals(type)).forEach(i->corpus.delete(i.id()));corpus.create(type,text,true);}
  void tick(long delta){now.addAndGet(delta);room.heartbeat("live");room.tick();}
  @Test void crudPersistsAndValidatesPlaceholdersAndLimits(){
    corpus.create("gift","谢谢{nickname}的{giftCount}个{giftName}",true);var item=items().getLast();
    corpus.update(item.id(),"感谢{nickname}",false);assertTrue(fresh().status("gift").toString().contains("感谢"));assertFalse(items().getLast().enabled());
    corpus.delete(item.id());assertFalse(fresh().status(null).toString().contains(item.id()));
    assertThrows(IllegalArgumentException.class,()->corpus.create("idle","{nickname}",true));
    assertThrows(IllegalArgumentException.class,()->corpus.create("gift","{unknown}",true));
    assertThrows(IllegalArgumentException.class,()->corpus.create("welcome","{nickname",true));
    assertThrows(IllegalArgumentException.class,()->corpus.create("welcome","字".repeat(161),true));
  }
  @Test void welcomeAndGiftDirectlyProduceTemplatesWithoutAi(){
    only("welcome","欢迎{nicknames}共{count}人");only("gift","谢{nickname}的{giftCount}个{giftName}");
    room.subscribe("live",true);room.accept(new LiveEvent("enter1",EventType.USER_ENTER,"a","小明","","",0,now.get()));room.accept(new LiveEvent("enter2",EventType.USER_ENTER,"b","小红","","",0,now.get()));
    tick(1700);assertEquals("欢迎小明、小红共2人",current().get("text"));room.complete(current().get("taskId").toString(),"live");
    room.accept(new LiveEvent("gift1",EventType.GIFT,"a","小明","","小心心",3,now.get()));assertEquals("谢小明的3个小心心",current().get("text"));assertEquals(0,current().get("priority"));
    assertThrows(ProviderException.class,()->room.authorizeSpeech(current().get("taskId").toString(),"observer"));
  }
  @Test void idleRequiresOwnerRecentRealActivityAudioAndInterval(){
    only("idle","小助手等你聊天呀");corpus.idle(new CorpusService.Idle(true,30,60));room.subscribe("live",true);
    tick(31000);assertTrue(current().isEmpty());
    room.accept(new LiveEvent("mock-like",EventType.LIKE,"a","小明","","",0,now.get()));tick(31000);assertTrue(current().isEmpty());
    room.accept(new LiveEvent("real-like",EventType.LIKE,"a","小明","","",0,now.get()));room.tick();assertEquals("小助手等你聊天呀",current().get("text"));
    room.complete(current().get("taskId").toString(),"live");tick(29000);assertTrue(current().isEmpty());tick(1001);assertFalse(current().isEmpty());
    room.complete(current().get("taskId").toString(),"live");tick(31000);assertTrue(current().isEmpty());
  }
  @Test void idleDisabledOrNoLiveOwnerNeverSpeaks(){
    corpus.idle(new CorpusService.Idle(true,30,60));room.subscribe("preview",false);room.accept(new LiveEvent("like",EventType.LIKE,"a","小明","","",0,now.get()));tick(31000);assertTrue(current().isEmpty());
    room.subscribe("live",true);room.audio(new AudioSettings(false,1,1,""));room.tick();assertTrue(current().isEmpty());
    room.audio(new AudioSettings(true,1,1,""));corpus.idle(new CorpusService.Idle(false,30,60));room.tick();assertTrue(current().isEmpty());
  }
  @Test void historyDoesNotProduceSpeechAndDisabledListsHaveLocalFallback(){
    room.subscribe("live",true);assertEquals("historical",room.accept(new LiveEvent("douyin-old-gift",EventType.GIFT,"a","小明","","小心心",3,now.get()-60000)).get("reason"));assertTrue(current().isEmpty());
    items().stream().filter(i->i.type().equals("gift")).forEach(i->corpus.delete(i.id()));
    room.accept(new LiveEvent("gift",EventType.GIFT,"a","小明","","小心心",3,now.get()));assertEquals("谢谢小明送来的3个小心心！",current().get("text"));
  }
}
