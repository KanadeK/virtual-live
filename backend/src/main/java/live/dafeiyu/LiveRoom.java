package live.dafeiyu;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.function.LongSupplier;
import java.io.IOException;
import live.dafeiyu.Protocol.*;

/** Single-room, synchronized in-memory dispatcher. Only a connected /live owner can ack. */
@Service
public class LiveRoom {
  public static final int MAX_QUEUE=20;
  private final CaptureChatMetrics chatMetrics=new CaptureChatMetrics();
  private final AiReplyService ai;
  private IntegrationConfig integration;private UsageLedger usage;private DoubaoTtsService tts;
  private FishBalanceLedger fishBalance;
  @org.springframework.beans.factory.annotation.Autowired void fishBalance(FishBalanceLedger ledger){fishBalance=ledger;}
  private CorpusService corpus;
  @org.springframework.beans.factory.annotation.Autowired void corpus(CorpusService service){corpus=service;}
  private final java.util.concurrent.ExecutorService generation=java.util.concurrent.Executors.newSingleThreadExecutor(r->Thread.ofPlatform().daemon().name("ai-reply").unstarted(r));
  @org.springframework.beans.factory.annotation.Autowired void integrations(IntegrationConfig config,UsageLedger ledger,DoubaoTtsService voice){integration=config;usage=ledger;tts=voice;}
  @jakarta.annotation.PreDestroy public void shutdown(){generation.shutdownNow();}
  private final LongSupplier clock;
  private final LinkedHashMap<String,Client> clients=new LinkedHashMap<>();
  private final PriorityQueue<SpeechTask> queue=new PriorityQueue<>(Comparator.comparingInt(SpeechTask::priority).thenComparingLong(SpeechTask::createdAt));
  private final LinkedHashMap<String,Long> seen=new LinkedHashMap<>();
  private final Map<String,Long> clientPings=new HashMap<>();
  private final Map<String,Long> commentTimes=new HashMap<>();
  private final List<String> arrivals=new ArrayList<>();
  private boolean arrivalsAutomatic=true;
  private final Deque<LogEntry> logs=new ArrayDeque<>();
  private String owner;
  private String ownerEpoch="";
  private NativeMusicBridge nativeMusic;
  synchronized void nativeMusic(NativeMusicBridge bridge){nativeMusic=bridge;}
  synchronized NativeMusicBridge nativeMusic(){return nativeMusic;}
  synchronized boolean nativeBlocksHtml(){return nativeMusic!=null&&nativeMusic.blocksHtml();}
  synchronized void nativeMusicUpdate(Map<String,Object> value){broadcast("NATIVE_MUSIC_STATE",value);}
  synchronized boolean isCurrentSpeech(String taskId){return current!=null&&current.taskId().equals(taskId)&&!current.text().isEmpty();}
  synchronized boolean audioEnabled(){return audio.enabled();}
  synchronized String taskIdForEvent(String eventId){return current!=null&&current.event()!=null&&Objects.equals(current.event().eventId(),eventId)?current.taskId():"";}
  private void assignOwner(String clientId){owner=clientId;ownerEpoch=clientId==null?"":id();if(nativeMusic!=null)nativeMusic.ownerChanged();}
  private volatile SpeechTask current;
  private volatile SemanticMusicRouter.Handler musicRequests;
  void musicRequests(SemanticMusicRouter.Handler handler){musicRequests=handler;}
  private long deadline,arrivalAt,lastHeartbeat;
  private long lastRealActivity=-1,lastSpeechActivity;
  private long online=128,likes=2680,gifts,pendingLikes,dropped;
  private AudioSettings audio=new AudioSettings(true,1.05,1.25,"");
  private record Client(SseEmitter emitter,boolean live) {}
  @org.springframework.beans.factory.annotation.Autowired
  public LiveRoom(AiReplyService ai){this(ai,System::currentTimeMillis);}
  LiveRoom(AiReplyService ai,LongSupplier clock){this.ai=ai;this.clock=clock;lastSpeechActivity=clock.getAsLong();}
  private String id(){return UUID.randomUUID().toString();}
  private void log(String type,String text){logs.addFirst(new LogEntry(id(),clock.getAsLong(),type,text));while(logs.size()>80)logs.removeLast();}
  public synchronized SseEmitter subscribe(String clientId,boolean live){
    SseEmitter emitter=new SseEmitter(0L);
    Client previous=clients.put(clientId,new Client(emitter,live));clientPings.put(clientId,clock.getAsLong());
    if(previous!=null) previous.emitter.complete();
    if(Objects.equals(owner,clientId)){ownerEpoch=id();if(nativeMusic!=null)nativeMusic.ownerChanged();}
    emitter.onCompletion(()->disconnect(clientId,emitter));
    emitter.onTimeout(()->disconnect(clientId,emitter));
    emitter.onError(e->disconnect(clientId,emitter));
    send(clientId,"SNAPSHOT",status());
    if(owner==null&&live){assignOwner(clientId);broadcast("SYSTEM_NOTICE",Map.of("text","主播客户端已连接"));}
    if(current!=null&&!current.text().isEmpty())send(clientId,"SPEAK",speechPayload(current));
    dispatch();broadcastQueue();return emitter;
  }
  private synchronized void disconnect(String clientId,SseEmitter emitter){
    Client c=clients.get(clientId);if(c==null||c.emitter!=emitter)return;
    clients.remove(clientId);clientPings.remove(clientId);
    if(Objects.equals(owner,clientId)){
      assignOwner(clients.entrySet().stream().filter(e->e.getValue().live).map(Map.Entry::getKey).findFirst().orElse(null));
      if(current!=null){if(nativeMusic!=null)nativeMusic.speechEnded(current.taskId());enqueue(current);current=null;}
      dispatch();broadcastQueue();
    }
  }
  private void send(String clientId,String type,Object data){
    Client c=clients.get(clientId);if(c==null)return;
    try{c.emitter.send(SseEmitter.event().name("message").id(id()).reconnectTime(1500).data(Map.of("type",type,"data",data)));}
    catch(IOException|IllegalStateException ex){clients.remove(clientId);clientPings.remove(clientId);if(Objects.equals(owner,clientId))assignOwner(null);}
  }
  private void broadcast(String type,Object data){for(String k:new ArrayList<>(clients.keySet()))send(k,type,data);}
  public synchronized boolean heartbeat(String clientId){if(!clients.containsKey(clientId))return false;clientPings.put(clientId,clock.getAsLong());return true;}
  public synchronized boolean isOwner(String clientId){return clientId!=null&&Objects.equals(owner,clientId)&&clients.containsKey(clientId);}
  public synchronized String ownerEpoch(String clientId){return isOwner(clientId)?ownerEpoch:"";}
  synchronized String ownerEpoch(){return ownerEpoch;}
  public synchronized boolean isOwner(String clientId,String epoch){return epoch!=null&&!epoch.isBlank()&&isOwner(clientId)&&clients.get(clientId).live&&ownerEpoch.equals(epoch);}
  public synchronized void musicUpdate(Map<String,Object> status){broadcast("MUSIC_UPDATE",status);}
  synchronized void captureChatStatus(String platform,String roomId,String state,boolean trusted){chatMetrics.status(platform,roomId,state,trusted,clock.getAsLong());}
  synchronized void captureChat(String platform,String roomId,String id,String userId,String text,long timestamp){chatMetrics.chat(platform,roomId,id,userId,text,timestamp,clock.getAsLong());}
  void captureGift(String platform,String roomId,String id,long delta,String mode,long num,String coin,String total,long at,String group,String alias,long cumulative){
    FishBalanceLedger ledger;boolean accepted;
    synchronized(this){
      long now=clock.getAsLong();chatMetrics.gift(platform,roomId,id,delta,mode,num,coin,total,at,now);
      ledger=fishBalance;accepted=chatMetrics.admitsGiftBalance(platform,roomId,at,now);
    }
    if(ledger!=null&&accepted)ledger.gift(platform,roomId,id,mode,num,coin,total,at,group,alias,cumulative);
  }
  public synchronized void captureEvent(Object event){broadcast("CAPTURE_EVENT",event);}
  public synchronized void captureStatus(Object status){broadcast("CAPTURE_STATUS",status);}
  public synchronized void captureReset(){captureReset("douyin");}
  public synchronized void captureReset(String platform){broadcast("CAPTURE_RESET",Map.of("platform",platform));}
  public synchronized void release(String clientId){Client client=clients.get(clientId);if(client!=null){disconnect(clientId,client.emitter);client.emitter.complete();}}
  public synchronized Map<String,Object> accept(LiveEvent raw){
    return accept(raw,false);
  }
  // Bilibili commands share comment dedupe, history, activity and viewer throttling without creating speech.
  synchronized Map<String,Object> acceptMusicCommand(LiveEvent raw){
    return accept(raw,true);
  }
  private Map<String,Object> accept(LiveEvent raw,boolean musicCommand){
    long now=clock.getAsLong();String eventId=raw.eventId()==null||raw.eventId().isBlank()?id():raw.eventId();
    if(seen.containsKey(eventId))return Map.of("accepted",false,"reason","duplicate");
    seen.put(eventId,now);while(seen.size()>3000)seen.remove(seen.keySet().iterator().next());
    if((eventId.startsWith("douyin-")||eventId.startsWith("bilibili-"))&&(raw.timestamp()<now-45000||raw.timestamp()>now+10000))return Map.of("accepted",false,"reason","historical");
    String name=InputFilter.name(raw.nickname());
    LiveEvent e=new LiveEvent(eventId,raw.type(),InputFilter.clean(raw.userId(),80),name,InputFilter.clean(raw.content(),160),InputFilter.name(raw.giftName()),raw.type()==EventType.GIFT?Math.max(1,raw.giftCount()):0,now);
    if(e.type()==EventType.COMMENT&&e.content().isBlank())throw new IllegalArgumentException("弹幕内容不能为空");
    if(!IntegrationConfig.automatic(e)&&e.type()!=EventType.SYSTEM)lastRealActivity=now;
    if(e.type()==EventType.LIKE){likes++;pendingLikes++;stats();return Map.of("accepted",true);}
    if(e.type()==EventType.COMMENT){
      String source=eventId.startsWith("douyin-")?"douyin":eventId.startsWith("bilibili-")?"bilibili":"local";
      String user=source+":"+(e.userId().isBlank()?name:e.userId());Long prev=commentTimes.get(user);
      if(prev!=null&&now-prev<4000){dropped++;log("LIMIT","已限制 "+name+" 的连续评论");broadcastQueue();return Map.of("accepted",false,"reason","rate_limited");}
      commentTimes.put(user,now);
    }
    log(musicCommand?"MUSIC":e.type().name(),name+(e.type()==EventType.GIFT?" 送出 "+e.giftName()+" ×"+e.giftCount():e.type()==EventType.USER_ENTER?" 来到直播间":"："+e.content()));
    if(musicCommand){stats();broadcastQueue();return Map.of("accepted",true);}
    if(e.type()==EventType.USER_ENTER){online++;if(arrivals.isEmpty()){arrivalAt=now;arrivalsAutomatic=true;}arrivals.add(name);arrivalsAutomatic&=IntegrationConfig.automatic(e);}
    else {
      if(e.type()==EventType.GIFT)gifts+=e.giftCount();
      String text=e.type()==EventType.GIFT&&corpus!=null?corpus.phrase("gift",Map.of("nickname",name,"giftName",e.giftName(),"giftCount",String.valueOf(e.giftCount()))):(ai.needsRemote(e)?"":ai.reply(e));
      enqueue(new SpeechTask(id(),text,e.type()==EventType.GIFT?"happy":"neutral",e.type()==EventType.GIFT?"celebrate":e.content().matches(".*(跳|舞).*" )?"happy":"talk",e.type()==EventType.GIFT?0:2,now,e));
    }
    stats();dispatch();broadcastQueue();return Map.of("accepted",true);
  }
  private void enqueue(SpeechTask task){
    prune();
    if(queue.size()>=MAX_QUEUE){
      SpeechTask worst=queue.stream().max(Comparator.comparingInt(SpeechTask::priority).thenComparingLong(t->-t.createdAt())).orElseThrow();
      if(task.priority()>worst.priority()){dropped++;return;}
      queue.remove(worst);dropped++;
    }
    queue.add(task);
  }
  private void prune(){long now=clock.getAsLong();int before=queue.size();queue.removeIf(t->now-t.createdAt()>(t.priority()==0?180000:45000));dropped+=before-queue.size();}
  private Map<String,Object> speechPayload(SpeechTask t){
    Map<String,Object> p=new HashMap<>();p.put("taskId",t.taskId());p.put("text",t.text());p.put("emotion",t.emotion());p.put("action",t.action());p.put("priority",t.priority());p.put("ownerId",owner==null?"":owner);p.put("ownerEpoch",ownerEpoch);p.put("startedAt",clock.getAsLong());p.put("ttsProvider",integration!=null&&integration.doubaoReady()&&(!IntegrationConfig.automatic(t.event())||integration.snapshot().paidAutoEnabled())?"doubao":"browser");return p;
  }
  private void dispatch(){
    if(current!=null||owner==null)return;prune();
    current=queue.poll();if(current==null)return;
    if(current.text().isEmpty()){
      SpeechTask preparing=current;deadline=clock.getAsLong()+((integration==null?20:integration.snapshot().requestTimeoutSeconds())+8)*1000L;
      generation.submit(()->{
        String text;
        try{
          AiDecision decision=ai.decide(preparing.event());
          if(decision==null)decision=AiDecision.chat(ai.reply(preparing.event()));
          if(decision.requestsMusic()) {
            var handler=musicRequests;
            java.util.function.BooleanSupplier active=()->{var task=current;return task!=null&&task.taskId().equals(preparing.taskId());};
            text=handler==null?"点歌服务还未接好，这次没有加入队列。":handler.request(preparing.event(),decision,active);
          } else text=decision.reply();
        }catch(Exception failure){text="主播这次没处理成功，请稍后再试。";}
        synchronized(this){if(current==null||!current.taskId().equals(preparing.taskId()))return;
          current=new SpeechTask(preparing.taskId(),text,preparing.emotion(),preparing.action(),preparing.priority(),preparing.createdAt(),preparing.event());
          if(!ai.status().getOrDefault("lastFallback","").toString().isEmpty())log("PROVIDER","AI 暂时不可用，已明确回退本地回复");
          beginSpeech();broadcastQueue();
        }
      });
      return;
    }
    beginSpeech();
  }
  private void beginSpeech(){
    lastSpeechActivity=clock.getAsLong();
    deadline=clock.getAsLong()+Math.max(30000,Math.min(120000,current.text().length()*650L+26000));
    if(current.event()!=null&&current.event().type()==EventType.GIFT)broadcast("GIFT_EFFECT",Map.of("nickname",current.event().nickname(),"giftName",current.event().giftName(),"giftCount",current.event().giftCount()));
    broadcast("SPEAK",speechPayload(current));
  }
  public record SpeechAuthorization(String text,boolean enabled,boolean automatic,String voice) {}
  public synchronized SpeechAuthorization authorizeSpeech(String taskId,String clientId){
    if(current==null||!current.taskId().equals(taskId)||!Objects.equals(owner,clientId)||current.text().isEmpty())throw new ProviderException("NOT_ACTIVE_SPEAKER",409);
    return new SpeechAuthorization(current.text(),audio.enabled(),IntegrationConfig.automatic(current.event()),audio.voice());
  }
  public synchronized void audioPrepared(String taskId,String clientId,double duration){
    authorizeSpeech(taskId,clientId);deadline=Math.max(deadline,clock.getAsLong()+(long)(Math.max(0,duration)*1000)+10000);
  }
  public synchronized boolean complete(String taskId,String clientId){
    if(current==null||!current.taskId().equals(taskId)||!Objects.equals(owner,clientId))return false;
    lastSpeechActivity=clock.getAsLong();log("DONE","播报完成");if(nativeMusic!=null)nativeMusic.speechEnded(current.taskId());broadcast("SPEECH_END",Map.of("taskId",current.taskId()));current=null;dispatch();broadcastQueue();return true;
  }
  public synchronized void manual(String text){
    text=InputFilter.clean(text,160);if(text.isBlank())throw new IllegalArgumentException("台词不能为空");
    if(!InputFilter.safe(text))throw new IllegalArgumentException("台词包含不适合公开播报的内容");
    enqueue(new SpeechTask(id(),text,"happy","talk",1,clock.getAsLong(),null));log("MANUAL",text);dispatch();broadcastQueue();
  }
  public synchronized void action(Action action){
    if(action.action()==null&&action.emotion()==null)throw new IllegalArgumentException("请选择动作或表情");
    Map<String,String> data=new HashMap<>();if(action.action()!=null)data.put("action",action.action());if(action.emotion()!=null)data.put("emotion",action.emotion());broadcast("CHARACTER_ACTION",data);
  }
  public synchronized void audio(AudioSettings settings){audio=new AudioSettings(settings.enabled(),settings.rate(),settings.pitch(),settings.voice()==null?"":settings.voice().trim());broadcast("AUDIO_SETTINGS",audio);}
  public synchronized void clear(){queue.clear();arrivals.clear();log("SYSTEM","等待队列已清空，当前播报继续");broadcastQueue();}
  public synchronized Map<String,Object> status(){
    Map<String,Object> s=new HashMap<>();s.put("audience",chatMetrics.snapshot(clock.getAsLong()));Map<String,Object> finance=chatMetrics.finance(clock.getAsLong());
    Map<String,Object> balance=fishBalance==null?Map.of("available",false):fishBalance.status();
    finance.put("balanceFen",balance.get("balanceFen"));finance.put("fishBalance",balance);
    Map<String,Object> usageState=usage==null?Map.of():usage.status();Object rawEstimate=usageState.get("costEstimate");
    if(rawEstimate instanceof Map<?,?> estimate){finance.put("costFen",estimate.get("costFen"));finance.put("costBasis","按量估算·优惠未减");finance.put("costEstimate",estimate);}
    s.put("finance",finance);s.put("online",online);s.put("likes",likes);s.put("gifts",gifts);s.put("queueSize",queue.size());s.put("queue",queue.stream().sorted(queue.comparator()).map(t->Map.of("taskId",t.taskId(),"text",t.text().isEmpty()?"正在等待生成回复…":t.text(),"priority",t.priority())).toList());s.put("current",current==null?Map.of():speechPayload(current));s.put("ownerConnected",owner!=null);s.put("ownerId",owner==null?"":owner);s.put("ownerEpoch",ownerEpoch);s.put("dropped",dropped);s.put("audio",audio);s.put("logs",List.copyOf(logs));s.put("aiProvider",ai.provider());s.put("aiStatus",ai.status());s.put("phase",current==null?"IDLE":current.text().isEmpty()?"PREPARING_AI":"PLAYING");if(tts!=null)s.put("ttsStatus",tts.status());if(usage!=null)s.put("usage",usageState);if(integration!=null)s.put("paidAutoEnabled",integration.snapshot().paidAutoEnabled());return s;
  }
  private void stats(){broadcast("STAT_UPDATE",Map.of("online",online,"likes",likes,"gifts",gifts));}
  private void broadcastQueue(){broadcast("QUEUE_UPDATE",status());}
  @Scheduled(fixedDelay=500) public synchronized void tick(){
    long now=clock.getAsLong();
    for(String stale:new ArrayList<>(clientPings.keySet())){if(now-clientPings.getOrDefault(stale,now)>15000)release(stale);}
    if(!arrivals.isEmpty()&&now-arrivalAt>=1600){
      String names=String.join("、",arrivals.subList(0,Math.min(3,arrivals.size())));
      String text=arrivals.size()==1?"欢迎"+names+"来到小助手的直播间！小助手等你好久啦！":"欢迎"+names+(arrivals.size()>3?"等 "+arrivals.size()+" 位朋友":"")+"来到直播间！快坐好，小助手给大家比个心！";
      if(corpus!=null)text=corpus.phrase("welcome",Map.of("nickname",names,"nicknames",names,"count",String.valueOf(arrivals.size())));
      LiveEvent welcomeSource=arrivalsAutomatic?new LiveEvent("mock-welcome-"+id(),EventType.USER_ENTER,"","",null,null,0,now):null;
      enqueue(new SpeechTask(id(),text,"happy","wave",3,now,welcomeSource));arrivals.clear();
    }
    if(current!=null&&now>=deadline){log("TIMEOUT","播报超时，自动恢复队列");if(nativeMusic!=null)nativeMusic.speechEnded(current.taskId());broadcast("SPEECH_CANCEL",Map.of("taskId",current.taskId()));current=null;}
    if(pendingLikes>0){broadcast("LIKE_EFFECT",Map.of("count",pendingLikes));pendingLikes=0;}
    if(now-lastHeartbeat>5000){lastHeartbeat=now;broadcast("HEARTBEAT",Map.of("time",now));seen.entrySet().removeIf(e->now-e.getValue()>300000);commentTimes.entrySet().removeIf(e->now-e.getValue()>60000);}
    // Select a surviving live client after a transport write failure.
    if(owner==null&&current!=null){enqueue(current);current=null;}
    if(owner==null)assignOwner(clients.entrySet().stream().filter(e->e.getValue().live).map(Map.Entry::getKey).findFirst().orElse(null));
    if(corpus!=null&&owner!=null&&audio.enabled()&&current==null&&queue.isEmpty()&&arrivals.isEmpty()){
      CorpusService.Idle idle=corpus.idle();
      if(idle.enabled()&&lastRealActivity>=0&&now-lastRealActivity<=idle.requireRecentActivitySeconds()*1000L&&now-lastSpeechActivity>=idle.intervalSeconds()*1000L){
        String text=corpus.phrase("idle",Map.of());lastSpeechActivity=now;
        if(!text.isBlank())enqueue(new SpeechTask(id(),text,"happy","talk",4,now,null));
      }
    }
    dispatch();broadcastQueue();
  }
}
