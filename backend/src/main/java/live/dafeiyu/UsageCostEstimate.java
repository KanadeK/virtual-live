package live.dafeiyu;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;

/** New-request public-list-price estimate only; never writes or re-prices the persisted usage ledger. */
final class UsageCostEstimate {
  private final long startedAt=System.currentTimeMillis();
  private long requests,responses,unpriced;
  private BigDecimal yuan=BigDecimal.ZERO;
  private boolean upperBound;
  private final Deque<Map<String,Object>> recent=new ArrayDeque<>();
  void begin(){requests++;}
  static boolean endpoint(String url,String host){try{var u=URI.create(url);return "https".equals(u.getScheme())&&host.equals(u.getHost())&&u.getUserInfo()==null;}catch(Exception e){return false;}}
  void ai(long prompt,long output,long hit,String requested,String returned,String url,long requestAt,long created,boolean fieldsPresent){
    responses++;
    if(!fieldsPresent||!"deepseek-flash".equals(requested)||(!returned.isBlank()&&!Set.of("deepseek-flash","deepseek-v4.1-flash","deepseek-v4.1-flash-1017").contains(returned))
        ||!endpoint(url,"api.deepseek.com")||hit<0||prompt<hit||output<0){unpriced++;return;}
    long createdAt=created>0&&created<Long.MAX_VALUE/1000?created*1000:0;
    long at=createdAt>=requestAt-600000&&createdAt<=requestAt+600000?createdAt:requestAt;
    var time=Instant.ofEpochMilli(at).atZone(ZoneId.of("Asia/Shanghai"));int hour=time.getHour();
    boolean peak=time.getDayOfWeek().getValue()<6&&((hour>=9&&hour<12)||(hour>=14&&hour<18));
    // Workday peak is a published upper bound if a Chinese statutory holiday was not established.
    BigDecimal factor=peak?new BigDecimal("2"):BigDecimal.ONE;
    BigDecimal charge=BigDecimal.valueOf(hit).multiply(new BigDecimal("0.02")).add(BigDecimal.valueOf(prompt-hit)).add(BigDecimal.valueOf(output).multiply(new BigDecimal("4"))).multiply(factor).movePointLeft(6);
    yuan=yuan.add(charge);upperBound|=peak;
    record(Map.of("kind","ai","requestedModel",requested,"responseModel",returned.isBlank()?"not_returned":returned,"requestAt",requestAt,"responseCreated",created,"rateTime",at,"peakUpperBound",peak,"promptTokens",prompt,"hitTokens",hit,"outputTokens",output));
  }
  void tts(long billed,String resource,String url,long requestAt,boolean fieldsPresent){
    responses++;
    if(!fieldsPresent||billed<0||!"seed-tts-2.0".equals(resource)||!endpoint(url,"openspeech.bytedance.com")){unpriced++;return;}
    yuan=yuan.add(BigDecimal.valueOf(billed).multiply(new BigDecimal("0.0003")));
    record(Map.of("kind","tts","resourceId",resource,"requestAt",requestAt,"textWords",billed));
  }
  private void record(Map<String,Object> value){recent.addLast(value);while(recent.size()>8)recent.removeFirst();}
  Map<String,Object> snapshot(boolean configured){
    boolean complete=(configured||requests>0)&&responses==requests&&unpriced==0;
    Map<String,Object> out=new LinkedHashMap<>();
    out.put("available",complete);out.put("costFen",complete?yuan.movePointRight(2):null);out.put("knownSubtotalFen",yuan.movePointRight(2));
    out.put("startedAt",startedAt);out.put("requests",requests);out.put("reported",responses);out.put("unpriced",unpriced);out.put("estimated",true);
    out.put("upperBound",upperBound);out.put("basis","接入后新请求·公示费率估算·优惠未核减，非实际扣款");
    out.put("reason",!configured?"no_cloud_provider":unpriced>0?"unpriced_usage":responses!=requests?"usage_not_returned":"priced_new_requests");
    out.put("ratesCheckedAt","2026-10-31");out.put("recentRecords",List.copyOf(recent));out.put("recentRecordsLimit",8);
    return out;
  }
}
