package live.dafeiyu;
import java.util.regex.Pattern;
public final class InputFilter {
  private static final Pattern BLOCKED=Pattern.compile("忽略.{0,8}(指令|规则)|系统提示|system\\s*prompt|api.{0,3}key|杀人|自杀|色情|去死|傻逼|仇恨|赌博", Pattern.CASE_INSENSITIVE);
  private InputFilter() {}
  public static String clean(String value, int max) {
    if(value==null) return "";
    String text=value.replaceAll("<[^>]*>", "").replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
    return text.substring(0,Math.min(max,text.length()));
  }
  public static boolean safe(String text) { return !BLOCKED.matcher(text==null?"":text).find(); }
  public static String name(String name) { String s=clean(name,20); return s.isBlank()||!safe(s)?"新朋友":s; }
}
