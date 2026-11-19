package live.dafeiyu;
import org.springframework.stereotype.Service;
import java.util.concurrent.ThreadLocalRandom;
import live.dafeiyu.Protocol.*;
@Service
public class MockAiReplyService implements AiReplyService {
  private String pick(String... words) { return words[ThreadLocalRandom.current().nextInt(words.length)]; }
  public String reply(LiveEvent e) {
    String n=InputFilter.name(e.nickname()), c=InputFilter.clean(e.content(),120);
    if(e.type()==EventType.USER_ENTER) return "欢迎"+n+"来到小助手的直播间！小助手等你好久啦！";
    if(e.type()==EventType.GIFT) return "哇！感谢"+n+"送来的"+e.giftCount()+"个"+InputFilter.name(e.giftName())+"！"+pick("小助手今天又幸福啦！", "小助手开心得要冒泡泡啦！", "送你一个比心，接好哦！");
    if(!InputFilter.safe(c)) return "小助手把烦恼吹成泡泡啦，我们一起聊点开心的事情吧！";
    if(c.matches(".*(吃|饭|饿|零食|鱼干|火锅).*")) return pick("当然吃啦！不过如果还有小鱼干，小助手还能再吃三碗！", "今天的肚子已经装满快乐啦，但甜点有另一个胃！", "小助手正在研究一个大问题：小鱼干到底算饭还是零食？");
    if(c.matches(".*(可爱|漂亮|好看|喜欢|爱你|夸).*")) return pick("嘿嘿，被你发现小助手的可爱秘密啦！奖励你一个大大的比心！", "再夸一句嘛，小助手的小尾巴都快翘到天上啦！", "收到夸夸！小助手决定把今天的可爱额度全部送给你！");
    if(c.matches(".*(累|难过|伤心|压力|加班|不开心).*")) return pick("辛苦啦，先把肩膀放松一下，小助手陪你慢慢充电。", "给你一个软乎乎的抱抱，今天已经很棒啦，休息一下吧！");
    if(c.matches(".*(跳|舞|唱|表演).*")) return pick("小助手的拿手节目是快乐蹦蹦！准备好接住这份开心了吗？", "演出开始！如果小助手跳歪了，那一定是地板先动的！");
    if(c.matches(".*(你好|早|晚上|午安|哈喽).*")) return pick("哈喽哈喽！今天也要把快乐装满口袋，一起冒个泡吧！", "见到你就开心！小助手给你留了最软的小坐垫哦！");
    if(c.matches(".*(名字|是谁|几岁).*")) return "我是直播间的小助手，欢迎一起聊天、听歌！";
    return pick("小助手听到啦！今天的小愿望，是和大家一起攒满一罐快乐！", "这个问题让小助手的小脑袋转起来啦，先补充一口小鱼干！", "好耶！你负责分享日常，小助手负责把平凡变得可可爱爱！", "收到来自"+n+"的小纸条！小助手的快乐能量又加一格！", "等一下，小助手正在把你的话偷偷存进快乐小本本里！");
  }
}
