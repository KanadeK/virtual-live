package live.dafeiyu;

import org.springframework.stereotype.Component;
import java.util.function.BooleanSupplier;
import live.dafeiyu.Protocol.LiveEvent;

/** Network work runs outside LiveRoom's monitor; capture generation is checked again at commit. */
@Component
public final class SemanticMusicRouter {
  private final java.util.LinkedHashMap<String,String> receipts=new java.util.LinkedHashMap<>();
  public SemanticMusicRouter(LiveRoom room,MusicService music,BilibiliCaptureService bilibili) {
    room.musicRequests((event,decision,active)->route(event,decision,active,music,bilibili));
  }
  private synchronized String route(LiveEvent event,AiDecision decision,BooleanSupplier active,MusicService music,BilibiliCaptureService bilibili) {
      if(!active.getAsBoolean())return "这条点歌已过期，没有加入队列。";
      String eventId=event.eventId();
      if(eventId==null||eventId.isBlank())return "点歌缺少事件编号，没有加入队列。";
      if(receipts.containsKey(eventId))return receipts.get(eventId);
      if(event.content()!=null&&event.content().strip().matches("(?s)^(?:请|我|这次|现在|麻烦)*(?:不要|别|不想)(?:再)?(?:播放|放|播|听).*"))return "收到，这次不播放，也不修改队列。";
      String result;
      if(event.eventId()!=null&&event.eventId().startsWith("bilibili-"))
        result=bilibili.semanticRequest(event,decision,active);
      else if(event.eventId()!=null&&event.eventId().startsWith("douyin-"))
        result="抖音自然语言点歌尚未启用，这次没有加入队列。";
      else if(music.nativeBridge()!=null&&music.nativeBridge().enabled()){
        try{result=music.nativeBridge().console(decision,active,event.eventId(),music.nativeTaskId(event.eventId()));}
        catch(ProviderException unavailable){result=NativeSongFailure.message(unavailable,"QQ正常点歌尚未确认，这次没有报告播放成功。");}
      }else result=music.semanticRequest(decision,active);
      receipts.put(eventId,result);while(receipts.size()>3000)receipts.remove(receipts.keySet().iterator().next());return result;
  }
  @FunctionalInterface public interface Handler {
    String request(LiveEvent event,AiDecision decision,BooleanSupplier active);
  }
}

