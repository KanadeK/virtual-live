package live.dafeiyu;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Exact unique public chat accounts in this observed capture run, never a complete-day claim. */
final class CaptureChatMetrics {
  private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
  private static final int LIMIT=50000;
  private final GiftPushValue gifts=new GiftPushValue();
  private final Set<String> users=new HashSet<>(),events=new HashSet<>();
  private String platform="",roomId="",state="stopped",date="";
  private long startedAt,throughAt,balanceCaptureStartedAt;
  private boolean trusted,liveSeen,gaps,overflow;

  static boolean normalWorker(String platform,Path worker){
    String script="bilibili".equals(platform)?"../collector/dist/bilibili/worker.js":"../collector/dist/worker.js";
    return worker.equals(Path.of(script).toAbsolutePath().normalize());
  }
  void status(String nextPlatform,String nextRoom,String nextState,boolean normalWorker,long now){
    boolean starting=Set.of("connecting","live","reconnecting").contains(nextState);
    boolean same=platform.equals(nextPlatform)&&roomId.equals(nextRoom);
    if(!same&&!starting)return; // An inactive second platform cannot erase the active source.
    if(!same||(!Set.of("connecting","live","reconnecting").contains(state)&&"connecting".equals(nextState))){
      users.clear();events.clear();gifts.clear();startedAt=throughAt=balanceCaptureStartedAt=0;liveSeen=gaps=overflow=false;
      platform=nextPlatform;roomId=nextRoom;
    }
    rollover(now);trusted=normalWorker;state=nextState;
    if("reconnecting".equals(state)&&liveSeen)gaps=true;
    if("live".equals(state)&&trusted&&!roomId.isBlank()){if(balanceCaptureStartedAt==0)balanceCaptureStartedAt=now;if(!liveSeen){startedAt=now;liveSeen=true;}throughAt=now;}
    else if(liveSeen)throughAt=now;
  }
  void chat(String source,String room,String eventId,String userId,String content,long occurredAt,long now){
    rollover(now);
    if(!trusted||!liveSeen||!"live".equals(state)||!platform.equals(source)||!roomId.equals(room)
        ||eventId==null||eventId.isBlank()||eventId.length()>200||userId==null||userId.isBlank()||userId.length()>100
        ||content==null||content.isBlank()||occurredAt<startedAt||occurredAt<now-45000||occurredAt>now+10000)return;
    if("bilibili".equals(source)&&!userId.matches("[1-9]\\d{0,29}"))return;
    if(eventId.matches("(?i)^(mock|fixture|test|manual)[-_:].*")||userId.matches("(?i)^(mock|fixture|test|manual)[-_:].*"))return;
    if(events.contains(eventId))return;
    if(events.size()>=LIMIT||users.size()>=LIMIT){overflow=true;return;} // Never evict and pretend the count is complete.
    events.add(eventId);users.add(userId);throughAt=now;
  }
  void gift(String source,String room,String id,long delta,String mode,long num,String coin,String totalCoin,long at,long now){
    rollover(now);
    if(trusted&&liveSeen&&"live".equals(state)&&platform.equals(source)&&roomId.equals(room)&&at>=startedAt&&at>=now-45000&&at<=now+10000)
      gifts.event(id,delta,mode,num,coin,totalCoin);
  }
  boolean admitsGiftBalance(String source,String room,long at,long now){
    return trusted&&liveSeen&&"live".equals(state)&&platform.equals(source)&&roomId.equals(room)
        &&balanceCaptureStartedAt>0&&at>=balanceCaptureStartedAt&&at>=now-45000&&at<=now+10000;
  }
  Map<String,Object> finance(long now){rollover(now);var out=gifts.snapshot(trusted&&liveSeen&&"bilibili".equals(platform));out.put("incomeStartedAt",liveSeen?startedAt:null);return out;}
  private void rollover(long now){
    String today=Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate().toString();
    if(!today.equals(date)){
      boolean continueLive=trusted&&liveSeen&&"live".equals(state);
      date=today;users.clear();events.clear();gifts.clear();overflow=false;
      liveSeen=continueLive;startedAt=continueLive?now:0;throughAt=continueLive?now:0;
    }
  }
  Map<String,Object> snapshot(long now){
    rollover(now);boolean available=trusted&&liveSeen&&!overflow;
    Map<String,Object> s=new LinkedHashMap<>();
    s.put("available",available);s.put("uniqueChatters",available?users.size():null);
    s.put("scope","capture_run");s.put("date",date);s.put("timeZone","Asia/Shanghai");
    s.put("platform",platform);s.put("roomId",roomId);s.put("state",state);
    s.put("startedAt",liveSeen?startedAt:null);s.put("throughAt",liveSeen?throughAt:null);
    s.put("hadGaps",gaps);s.put("observedEvents",events.size());
    s.put("reason",overflow?"capacity_reached":!trusted?"untrusted_or_no_capture":!liveSeen?"not_live_yet":"observed_capture_only");
    return s;
  }
}
