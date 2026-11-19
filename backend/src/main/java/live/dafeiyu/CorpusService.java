package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Small local corpus; updates take effect before the next phrase is selected. */
@Service
public final class CorpusService {
  public record Item(String id,String type,String text,boolean enabled) {}
  public record Idle(boolean enabled,int intervalSeconds,int requireRecentActivitySeconds) {}
  public record State(List<Item> items,Idle idle,long revision) {}
  private static final Set<String> TYPES=Set.of("welcome","idle","gift");
  private static final Pattern PLACEHOLDER=Pattern.compile("\\{([^{}]+)}");
  private final ObjectMapper json;
  private final Path file;
  private volatile State state;
  public CorpusService(IntegrationConfig config,ObjectMapper json){
    this.json=json;file=config.dataDir.resolve("corpus.json");
    state=new State(defaults(),new Idle(false,120,300),0);
    try{if(Files.isRegularFile(file)){State saved=json.readValue(file.toFile(),State.class);validate(saved);state=new State(List.copyOf(saved.items()),saved.idle(),saved.revision());}}
    catch(Exception failure){throw new IllegalStateException("Local corpus unreadable; repair corpus.json before starting");}
  }
  private static List<Item> defaults(){return List.of(
    new Item("welcome-default-1","welcome","欢迎{nicknames}来到直播间！小助手给{count}位朋友比个心！",true),
    new Item("welcome-default-2","welcome","{nickname}来啦！快坐好，小助手等你好久啦！",true),
    new Item("idle-default-1","idle","有人想陪小助手聊聊天吗？今天有什么开心的小事呀？",true),
    new Item("idle-default-2","idle","小助手在这里等你，路过的小伙伴也可以打个招呼呀！",true),
    new Item("gift-default-1","gift","谢谢{nickname}送来的{giftCount}个{giftName}！小助手开心得冒泡啦！",true),
    new Item("gift-default-2","gift","收到{nickname}的{giftName}，一共{giftCount}个！给你一个大大的比心！",true));}
  public Map<String,Object> status(String type){
    if(type!=null&&!TYPES.contains(type))throw new IllegalArgumentException("未知语料类别");
    State s=state;return Map.of("items",s.items().stream().filter(i->type==null||i.type().equals(type)).toList(),"idle",s.idle(),"revision",s.revision());
  }
  public synchronized Map<String,Object> create(String type,String text,boolean enabled){
    checkText(type,text);State old=state;
    if(old.items().stream().filter(i->i.type().equals(type)).count()>=100)throw new IllegalArgumentException("每类最多100条语料");
    List<Item> next=new ArrayList<>(old.items());next.add(new Item(UUID.randomUUID().toString(),type,text.strip(),enabled));save(next,old.idle());return status(null);
  }
  public synchronized Map<String,Object> update(String id,String text,boolean enabled){
    Item previous=find(id);checkText(previous.type(),text);
    save(state.items().stream().map(i->i.id().equals(id)?new Item(id,i.type(),text.strip(),enabled):i).toList(),state.idle());return status(null);
  }
  public synchronized Map<String,Object> delete(String id){find(id);save(state.items().stream().filter(i->!i.id().equals(id)).toList(),state.idle());return status(null);}
  public synchronized Map<String,Object> idle(Idle idle){checkIdle(idle);save(state.items(),idle);return status(null);}
  Idle idle(){return state.idle();}
  private Item find(String id){return state.items().stream().filter(i->i.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("语料不存在"));}
  private void save(List<Item> items,Idle idle){State next=new State(List.copyOf(items),idle,state.revision()+1);AtomicJson.write(json,file,next,"CORPUS_WRITE_FAILED");state=next;}
  String phrase(String type,Map<String,String> fields){
    List<Item> choices=state.items().stream().filter(i->i.type().equals(type)&&i.enabled()).toList();
    String text=choices.isEmpty()?switch(type){case "welcome"->"欢迎{nicknames}来到直播间！小助手给大家比个心！";case "gift"->"谢谢{nickname}送来的{giftCount}个{giftName}！";default->"";}:choices.get(ThreadLocalRandom.current().nextInt(choices.size())).text();
    // Matcher substitution prevents placeholder-looking nicknames being interpreted a second time.
    var matcher=PLACEHOLDER.matcher(text);StringBuffer out=new StringBuffer();
    while(matcher.find())matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(fields.getOrDefault(matcher.group(1),"")));
    matcher.appendTail(out);return InputFilter.clean(out.toString(),160);
  }
  private static void validate(State s){
    if(s==null||s.items()==null||s.items().size()>300)throw new IllegalArgumentException();checkIdle(s.idle());Set<String> ids=new HashSet<>();
    for(Item i:s.items()){if(i==null||i.id()==null||i.id().isBlank()||!ids.add(i.id()))throw new IllegalArgumentException();checkText(i.type(),i.text());}
    for(String type:TYPES)if(s.items().stream().filter(i->i.type().equals(type)).count()>100)throw new IllegalArgumentException();
  }
  private static void checkIdle(Idle i){if(i==null||i.intervalSeconds()<30||i.intervalSeconds()>3600||i.requireRecentActivitySeconds()<30||i.requireRecentActivitySeconds()>3600)throw new IllegalArgumentException("空闲播报间隔和互动窗口须为30～3600秒");}
  private static void checkText(String type,String text){
    if(type==null||!TYPES.contains(type)||text==null||text.isBlank()||text.codePointCount(0,text.length())>160||!InputFilter.safe(text))throw new IllegalArgumentException("语料须为1～160字且内容适合公开播报");
    Set<String> allowed=switch(type){case "welcome"->Set.of("nickname","nicknames","count");case "gift"->Set.of("nickname","giftName","giftCount");default->Set.of();};
    var matcher=PLACEHOLDER.matcher(text);while(matcher.find())if(!allowed.contains(matcher.group(1)))throw new IllegalArgumentException("语料包含不支持的占位符");
    if(PLACEHOLDER.matcher(text).replaceAll("").matches("(?s).*[{}].*"))throw new IllegalArgumentException("语料占位符格式不正确");
  }
}
