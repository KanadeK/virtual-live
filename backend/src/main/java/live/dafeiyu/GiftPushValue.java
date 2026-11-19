package live.dafeiyu;
import java.math.BigDecimal;
import java.util.*;

/** SEND_GIFT total_coin is one transaction total; COMBO_SEND never contributes money. */
final class GiftPushValue {
  private final Set<String> seen=new HashSet<>();
  private long expectedItems,reportedItems,silverItems,goldTransactions;
  private boolean unknown;
  private BigDecimal gold=BigDecimal.ZERO;
  void clear(){seen.clear();expectedItems=reportedItems=silverItems=goldTransactions=0;unknown=false;gold=BigDecimal.ZERO;}
  void event(String id,long delta,String mode,long num,String coin,String totalCoin){
    if(seen.contains(id))return;
    if(seen.size()>=50000){unknown=true;return;}
    seen.add(id);
    expectedItems+=Math.max(0,delta);
    if(!"delta".equals(mode))return;
    if(num<=0){unknown=true;return;}reportedItems+=num;
    if("silver".equals(coin)){silverItems+=num;return;}
    if(!"gold".equals(coin)||totalCoin==null||!totalCoin.matches("\\d{1,16}")){unknown=true;return;}
    gold=gold.add(new BigDecimal(totalCoin));goldTransactions++;
  }
  Map<String,Object> snapshot(boolean captureKnown){
    boolean available=captureKnown&&!unknown&&reportedItems>=expectedItems&&!(silverItems>0&&goldTransactions==0);
    Map<String,Object> out=new LinkedHashMap<>();out.put("incomeFen",available?gold.movePointLeft(1):null);
    out.put("incomeBasis","本次B站金瓜子礼物推送总额，1000金瓜子=1元；非到账净收益");
    out.put("incomeReason",!captureKnown?"capture_unavailable":unknown||reportedItems<expectedItems?"gift_amount_missing":silverItems>0&&goldTransactions==0?"free_currency_only":"posted_gold_total");
    out.put("goldTransactions",goldTransactions);out.put("silverItems",silverItems);out.put("expectedItems",expectedItems);out.put("reportedItems",reportedItems);
    return out;
  }
}
