package live.dafeiyu;

import org.springframework.stereotype.Service;
import java.util.Map;

/** Serialize mutations across the two adapters. Validate before stopping the existing source. */
@Service
public class CaptureSwitch {
  private final DouyinCaptureService douyin;
  private final BilibiliCaptureService bilibili;
  public CaptureSwitch(DouyinCaptureService douyin, BilibiliCaptureService bilibili) { this.douyin = douyin; this.bilibili = bilibili; }
  public synchronized Map<String,Object> connect(String platform, String input, String cookie) {
    if ("bilibili".equals(platform)) { bilibili.validateConnect(input, cookie); douyin.disconnect(); return bilibili.connect(input, cookie); }
    douyin.validateConnect(input, cookie); bilibili.disconnect(); return douyin.connect(input, cookie);
  }
  public synchronized Map<String,Object> login(String platform, String input) {
    if ("bilibili".equals(platform)) { bilibili.validateLogin(input); douyin.disconnect(); return bilibili.login(input); }
    douyin.validateLogin(input); bilibili.disconnect(); return douyin.login(input);
  }
  public synchronized Map<String,Object> disconnect(String platform) { return "bilibili".equals(platform) ? bilibili.disconnect() : douyin.disconnect(); }
  public synchronized Map<String,Object> cancelLogin(String platform) { return "bilibili".equals(platform) ? bilibili.cancelLogin() : douyin.cancelLogin(); }
}
