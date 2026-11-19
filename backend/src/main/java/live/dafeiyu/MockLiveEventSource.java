package live.dafeiyu;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.ThreadLocalRandom;
import live.dafeiyu.Protocol.*;
@Component
public class MockLiveEventSource implements LiveEventSource {
  public static final List<String> COMMENTS=List.of("小助手，你今天吃饭了吗？","今天的小鱼干好吃吗？","小助手好可爱！","可以跳个舞吗？","晚上好呀小助手","今天工作有点累","你的发饰真好看","你最喜欢什么零食？","给小助手一个大大的拥抱","小助手今年几岁啦？","可以给我加油吗","我带着奶茶来看你啦","这里好温暖呀","小助手会不会游泳","我今天考试通过啦","今天外面下雨了","送你一朵小花花","周末打算做什么呀","小助手喜欢猫咪吗","今天想吃火锅","早上好小可爱","你的小房间好漂亮","小助手的梦想是什么","可以陪我聊聊天吗");
  private static final String[] NAMES={"小明","奶茶半糖","橘子汽水","晚风","小熊软糖","芝士饭团","月亮邮递员","桃桃"};
  private Consumer<LiveEvent> receiver; private boolean running; private long next; private int min,max;
  public MockLiveEventSource(@Value("${live.mock-min-seconds:5}") int min,@Value("${live.mock-max-seconds:15}") int max){configure(min,max);}
  public synchronized void configure(int min,int max){this.min=Math.max(5,min);this.max=Math.max(this.min,max);}
  public synchronized void start(Consumer<LiveEvent> receiver){this.receiver=receiver;running=true;next=System.currentTimeMillis()+1500;}
  public synchronized void stop(){running=false;}
  public synchronized boolean running(){return running;}
  @Scheduled(fixedDelay=1000) public synchronized void tick(){
    long now=System.currentTimeMillis();if(!running||now<next)return;
    ThreadLocalRandom r=ThreadLocalRandom.current();int n=r.nextInt(NAMES.length),roll=r.nextInt(10);
    EventType type=roll<3?EventType.USER_ENTER:roll<7?EventType.COMMENT:roll<9?EventType.GIFT:EventType.LIKE;
    receiver.accept(new LiveEvent("mock-"+UUID.randomUUID(),type,"mock-user-"+n,NAMES[n],COMMENTS.get(r.nextInt(COMMENTS.size())),pickGift(r),r.nextInt(1,11),now));
    next=now+r.nextLong(min*1000L,max*1000L+1);
  }
  private String pickGift(ThreadLocalRandom r){return new String[]{"小心心","小鱼干","鲜花","星星"}[r.nextInt(4)];}
}
