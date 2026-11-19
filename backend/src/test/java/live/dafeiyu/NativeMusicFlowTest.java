package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real semantic router + capture provenance + Room + native bridge, with only transport mocked. */
class NativeMusicFlowTest {
  final ObjectMapper json=new ObjectMapper();
  AiReplyService ai(AtomicInteger calls){return new AiReplyService(){public String reply(Protocol.LiveEvent e){throw new AssertionError("second AI");}public boolean needsRemote(Protocol.LiveEvent e){return true;}public AiDecision decide(Protocol.LiveEvent e){calls.incrementAndGet();return NativeMusicBridgeTest.SONG;}};}
  BilibiliCaptureService capture(NativeMusicBridgeTest.Fixture f){
    MusicService music=new MusicService(q->{throw new AssertionError("native request must not hit HTTP/local catalog");},f.room);
    var capture=new BilibiliCaptureService(json,f.room,music,"node","missing-fixture-worker");new SemanticMusicRouter(f.room,music,capture);
    ReflectionTestUtils.setField(capture,"state","live");ReflectionTestUtils.setField(capture,"roomId","123");ReflectionTestUtils.invokeMethod(capture,"publishStatus");return capture;
  }
  Object event(String id,String text){return Map.of("platform","bilibili","id",id,"roomId","123","type","chat","ts",System.currentTimeMillis(),"user",Map.of("id","9002","nickname","真实采集昵称"),"data",Map.of("content",text));}
  void activateAfterRequest(NativeMusicBridgeTest.Fixture f)throws Exception {NativeMusicBridgeTest.until(()->f.mode().equals("STOPPING_HTML"));assertTrue(f.bridge.htmlStopped("owner",f.epoch(),f.nonce(),true));f.executor.drain();}
  @Test void oneSemanticAiDecisionUsesTrustedCapturedUserAndNormalNativeEntry()throws Exception {
    AtomicInteger calls=new AtomicInteger();try(var f=new NativeMusicBridgeTest.Fixture(ai(calls))){f.clock.set(System.currentTimeMillis());var capture=capture(f);try{
      var row=json.valueToTree(event("semantic-one","我想听小宇"));capture.ingest(row);activateAfterRequest(f);NativeMusicBridgeTest.until(()->f.fake.count("prepare")==1);NativeMusicBridgeTest.until(()->!String.valueOf(((Map<?,?>)f.room.status().get("current")).get("text")).isBlank());f.executor.drain();
      assertEquals(1,f.fake.count("commit:"));assertEquals(1,calls.get());assertEquals("9002",f.fake.lastRequest.uid());assertEquals("bilibili",f.fake.lastRequest.source());assertEquals("小宇",f.fake.lastRequest.query());assertEquals("张震岳",f.fake.lastRequest.artist());assertEquals(0,f.fake.count("speech:true"));capture.ingest(row);assertEquals(1,calls.get());
    }finally{capture.shutdown();}}
  }
  @Test void actualSemanticCapturePathExplainsSelectionAmbiguityWithoutClaimingCommunicationFailure()throws Exception {
    AtomicInteger calls=new AtomicInteger();try(var f=new NativeMusicBridgeTest.Fixture(ai(calls))){f.clock.set(System.currentTimeMillis());var capture=capture(f);try{
      f.fake.prepareFailure=NativeSongFailure.rejected("ambiguous_song");capture.ingest(json.valueToTree(event("ambiguous-song","我想听小宇")));activateAfterRequest(f);
      NativeMusicBridgeTest.until(()->String.valueOf(((Map<?,?>)f.room.status().get("current")).get("text")).contains("完整版本名"));f.executor.drain();
      assertEquals(1,calls.get());assertEquals(0,f.fake.count("commit:"));assertTrue(String.valueOf(f.bridge.status().get("notice")).contains("完整版本名"));assertTrue(f.fake.count("cancel:")>0);
    }finally{capture.shutdown();}}
  }
  @Test void realRoomTimeoutDuringNativePreparationCannotCommitLate()throws Exception {
    AtomicInteger calls=new AtomicInteger();try(var f=new NativeMusicBridgeTest.Fixture(ai(calls))){f.clock.set(System.currentTimeMillis());var capture=capture(f);try{
      f.fake.prepareEntered=new CountDownLatch(1);f.fake.prepareResume=new CountDownLatch(1);capture.ingest(json.valueToTree(event("cancel-semantic","我想听小宇")));activateAfterRequest(f);NativeMusicBridgeTest.await(f.fake.prepareEntered);f.clock.addAndGet(31000);f.room.heartbeat("owner");f.room.tick();assertTrue(((Map<?,?>)f.room.status().get("current")).isEmpty());f.fake.prepareResume.countDown();NativeMusicBridgeTest.until(()->f.fake.count("cancel:")>0||((Map<?,?>)f.bridge.status().get("requests")).containsValue("INVALIDATED"));f.executor.drain();assertEquals(0,f.fake.count("commit:"));
    }finally{if(f.fake.prepareResume!=null)f.fake.prepareResume.countDown();capture.shutdown();}}
  }
  @Test void explicitBilibiliSongUsesNoAiAndStopsOnSourceChangeBeforeCommit()throws Exception {
    AtomicInteger calls=new AtomicInteger();try(var f=new NativeMusicBridgeTest.Fixture(ai(calls))){f.clock.set(System.currentTimeMillis());var capture=capture(f);try{
      f.fake.prepareEntered=new CountDownLatch(1);f.fake.prepareResume=new CountDownLatch(1);capture.ingest(json.valueToTree(event("explicit","点歌 小宇")));activateAfterRequest(f);NativeMusicBridgeTest.await(f.fake.prepareEntered);capture.disconnect();f.fake.prepareResume.countDown();NativeMusicBridgeTest.until(()->!((Map<?,?>)f.bridge.status().get("requests")).containsValue("PREPARING"));f.executor.drain();assertEquals(0,f.fake.count("commit:"));assertEquals(0,calls.get());
    }finally{if(f.fake.prepareResume!=null)f.fake.prepareResume.countDown();capture.shutdown();}}
  }
}
