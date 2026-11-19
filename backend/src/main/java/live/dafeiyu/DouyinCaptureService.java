package live.dafeiyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns one Lightweight collector; comments and arrivals enter the existing speech queue. */
@Service
public class DouyinCaptureService {
  public static final int MAX_EVENTS = 500;
  public record CaptureEvent(String id, String roomId, String type, long timestamp,
      String userId, String nickname, String content, String giftName, long giftCount, long comboTotal) {
    @com.fasterxml.jackson.annotation.JsonProperty("platform") public String platform() { return "douyin"; }
  }
  private final ObjectMapper json;
  private final LiveRoom room;
  private final String node;
  private final Path worker;
  private final ExecutorService reader = Executors.newCachedThreadPool(r -> Thread.ofPlatform().daemon().name("douyin-capture").unstarted(r));
  private final Deque<CaptureEvent> events = new ArrayDeque<>();
  private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
  private final LinkedHashMap<String, Long> combos = new LinkedHashMap<>();
  private record ComboProgress(long total,long lastAt,boolean ended) {}
  private final LinkedHashMap<String,ComboProgress> ungroupedCombos=new LinkedHashMap<>();
  private record PendingGift(CaptureEvent row,long count,long firstAt,long lastAt) {}
  private final LinkedHashMap<String,PendingGift> pendingGifts=new LinkedHashMap<>();
  private Process process;
  private Process loginProcess;
  private String loginState = "idle", loginMessage = "";
  private String roomId = "", state = "stopped", message = "尚未连接直播间";
  private boolean hasLogin;
  private long comments, gifts, members, lastEventAt, frames;
  private int attempts;

  public DouyinCaptureService(ObjectMapper json, LiveRoom room,
      @Value("${douyin.node:node}") String node,
      @Value("${douyin.worker:../collector/dist/worker.js}") String worker) {
    this.json = json; this.room = room; this.node = node; this.worker = Path.of(worker).toAbsolutePath().normalize();
  }

  static String parseRoom(String input) {
    String value = input == null ? "" : input.trim();
    if (value.matches("\\d{1,30}")) return value;
    java.util.regex.Matcher link = java.util.regex.Pattern.compile("https?://[^\\s<>]+").matcher(value);
    if (link.find()) value = link.group();
    try {
      URI uri = URI.create(value);
      if ("https".equals(uri.getScheme()) && "v.douyin.com".equalsIgnoreCase(uri.getHost()) && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getPath().matches("/[A-Za-z0-9_-]{1,80}/?"))
        return "https://v.douyin.com" + uri.getPath();
      if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || !"live.douyin.com".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null)
        throw new IllegalArgumentException();
      String path = uri.getPath();
      if (path.matches("/\\d{1,30}/?")) return path.replace("/", "");
    } catch (IllegalArgumentException ignored) {}
    throw new IllegalArgumentException("请输入房间号、网页直播链接或抖音分享链接");
  }

  void validateConnect(String input, String cookieInput) {
    parseRoom(input);
    String cookie = cookieInput == null ? "" : cookieInput.trim();
    if (cookie.length() > 16000 || cookie.contains("\n") || cookie.contains("\r") || cookie.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Cookie 格式不正确，请粘贴单行 Cookie 请求头的值");
    if (!Files.isRegularFile(worker)) throw new IllegalArgumentException("采集组件尚未构建，请在collector目录执行npm ci和npm run build");
  }
  void validateLogin(String input) {
    parseRoom(input);
    if (!Files.isRegularFile(worker.resolveSibling("login.js"))) throw new IllegalArgumentException("登录组件尚未构建，请先在collector目录运行npm run build");
  }
  public synchronized Map<String, Object> connect(String input, String cookieInput) {
    validateConnect(input, cookieInput);
    String nextRoom = parseRoom(input);
    String cookie = cookieInput == null ? "" : cookieInput.trim();
    if (cookie.length() > 16000 || cookie.contains("\n") || cookie.contains("\r"))
      throw new IllegalArgumentException("Cookie 格式不正确，请粘贴单行 Cookie 请求头的值");
    if (!Files.isRegularFile(worker)) throw new IllegalArgumentException("采集组件尚未构建，请运行 scripts/start-dev.sh 或在 collector 目录执行 npm ci && npm run build");
    stopLoginProcess();
    if (Set.of("opening", "waiting").contains(loginState)) { loginState = "idle"; loginMessage = ""; }
    stopProcess();
    if (!nextRoom.equals(roomId)) clearInternal();
    roomId = nextRoom;
    hasLogin = Arrays.stream(cookie.split(";")).map(String::trim)
      .anyMatch(v -> v.matches("(?:sessionid|sessionid_ss|sid_tt|sid_guard)=.+"));
    state = "connecting"; message = "正在启动 Lightweight 采集"; frames = 0; attempts = 0;
    try {
      ProcessBuilder builder = new ProcessBuilder(node, worker.toString()).directory(worker.getParent().getParent().toFile());
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      builder.environment().remove("DYHUB_COOKIE");
      Process child = builder.start();
      process = child;
      child.getOutputStream().write((json.writeValueAsString(Map.of("roomId", roomId, "cookie", cookie)) + "\n").getBytes(StandardCharsets.UTF_8));
      child.getOutputStream().flush();
      reader.submit(() -> read(child));
    } catch (IOException failure) {
      stopProcess(); state = "error"; message = "无法启动采集组件，请检查 Node.js 和 collector 依赖";
    }
    publishStatus();
    return snapshot();
  }

  public synchronized Map<String, Object> login(String input) {
    String loginRoom = parseRoom(input);
    Path script = worker.resolveSibling("login.js");
    if (!Files.isRegularFile(script)) throw new IllegalArgumentException("登录组件尚未构建，请先在 collector 目录运行 npm run build");
    stopLoginProcess();
    loginState = "opening"; loginMessage = "正在打开独立抖音登录窗口";
    try {
      Process child = new ProcessBuilder(node, script.toString()).directory(worker.getParent().getParent().toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
      loginProcess = child;
      child.getOutputStream().write((json.writeValueAsString(Map.of("roomId", loginRoom)) + "\n").getBytes(StandardCharsets.UTF_8));
      child.getOutputStream().flush();
      reader.submit(() -> readLogin(child, loginRoom));
    } catch (IOException failure) {
      stopLoginProcess(); loginState = "error"; loginMessage = "无法启动登录窗口，请检查 Chrome 和 Node.js";
    }
    publishStatus(); return snapshot();
  }

  private void readLogin(Process child, String loginRoom) {
    try (BufferedReader lines = child.inputReader(StandardCharsets.UTF_8)) {
      String line;
      while ((line = lines.readLine()) != null) {
        if (line.length() > 100000) continue;
        try {
          JsonNode wire = json.readTree(line);
          synchronized (this) {
            if (loginProcess != child) return;
            if ("credentials".equals(wire.path("kind").asText())) {
              loginProcess = null;
              loginState = "success"; loginMessage = "登录态已取得，已自动连接 Lightweight 采集";
              connect(loginRoom, wire.path("cookie").asText());
            } else if ("status".equals(wire.path("kind").asText())) {
              String next = wire.path("state").asText();
              if (!Set.of("opening", "waiting", "expired", "cancelled", "error").contains(next)) continue;
              loginState = next; loginMessage = clean(wire.path("message").asText(), 300); publishStatus();
            }
          }
        } catch (IOException | IllegalArgumentException ignored) {}
      }
    } catch (IOException ignored) {}
    synchronized (this) {
      if (loginProcess == child) {
        loginProcess = null;
        if (Set.of("opening", "waiting").contains(loginState)) { loginState = "error"; loginMessage = "登录窗口已退出，请重新尝试"; }
        publishStatus();
      }
    }
  }

  public synchronized Map<String, Object> cancelLogin() {
    stopLoginProcess(); loginState = "idle"; loginMessage = "登录已取消"; publishStatus(); return snapshot();
  }

  private void read(Process child) {
    try (BufferedReader lines = child.inputReader(StandardCharsets.UTF_8)) {
      String line;
      while ((line = lines.readLine()) != null) {
        if (line.length() > 100000) continue;
        try {
          JsonNode wire = json.readTree(line);
          synchronized (this) {
            if (process != child) return;
            if ("resolved".equals(wire.path("kind").asText()) && wire.path("roomId").asText().matches("\\d{1,30}")) {
              roomId = wire.path("roomId").asText(); publishStatus();
            }
            else if ("event".equals(wire.path("kind").asText())) {
              JsonNode event=wire.path("event");
              if (CaptureChatMetrics.normalWorker("douyin",worker) && "live".equals(state) && "chat".equals(event.path("type").asText()))
                room.captureChat("douyin",event.path("roomId").asText(),event.path("id").asText(),event.path("user").path("id").asText(),event.path("data").path("content").asText(),event.path("ts").asLong(0));
              ingest(event);
            }
            else if ("status".equals(wire.path("kind").asText())) {
              String next = wire.path("state").asText();
              if (!Set.of("connecting", "live", "closed", "error", "reconnecting").contains(next)) continue;
              state = next; message = clean(wire.path("message").asText(), 300);
              frames = wire.path("frameCount").asLong(frames); attempts = wire.path("attempts").asInt(attempts);
              publishStatus();
            }
          }
        } catch (IOException | IllegalArgumentException ignored) { /* Ignore malformed subprocess lines. */ }
      }
    } catch (IOException ignored) { /* Stop closes the pipe. */ }
    synchronized (this) {
      if (process == child) {
        process = null;
        if (!state.equals("closed") && !state.equals("error")) {
          state = "error"; message = "采集进程已退出，请重新连接"; publishStatus();
        }
      }
    }
  }

  // Package visibility permits protocol tests without contacting Douyin.
  synchronized void ingest(JsonNode event) {
    String type = event.path("type").asText();
    if (!Set.of("chat", "gift", "member").contains(type)) return;
    String sourceRoom = event.path("roomId").asText();
    if (!sourceRoom.equals(roomId)) return;
    String sourceId = clean(event.path("id").asText(), 120);
    if (sourceId.isBlank() || sourceId.equals("0")) sourceId = UUID.randomUUID().toString();
    String key = sourceRoom + ":" + type + ":" + sourceId;
    if (seen.putIfAbsent(key, true) != null) return;
    trim(seen, 5000);
    JsonNode user = event.path("user"), data = event.path("data");
    String userId = clean(user.path("id").asText(), 100);
    String nickname = clean(user.path("nickname").asText(), 80);
    if (nickname.isBlank()) nickname = "匿名观众";
    long count = 0, total = 0;String giftGroup=key;
    if (type.equals("gift")) {
      total = Math.max(1, Math.min(1000000, data.path("repeatCount").asLong(data.path("comboCount").asLong(1))));
      String group = data.path("groupId").asText("");
      if (group.isBlank() || group.equals("0")) group = data.path("traceId").asText("");
      if (!group.isBlank() && !group.equals("0")) {
        String comboKey = sourceRoom + ":" + userId + ":" + data.path("giftId").asText() + ":" + group;
        giftGroup=comboKey;
        long previous = combos.getOrDefault(comboKey, 0L);
        count = Math.max(0, total - previous);
        combos.put(comboKey, Math.max(previous, total)); trim(combos, 2000);
      } else {
        // Some platform frames omit both groupId and traceId. Retain short-lived
        // per-user/gift progress so an ending cumulative frame is not thanked twice.
        giftGroup=sourceRoom+":"+userId+":"+data.path("giftId").asText()+":ungrouped";
        long received=System.currentTimeMillis();boolean ended=data.path("repeatEnd").asBoolean(false);
        ComboProgress previous=ungroupedCombos.get(giftGroup);
        boolean standalone=!data.path("combo").asBoolean(false)&&total==1&&!ended;
        boolean reset=previous==null||received-previous.lastAt()>10000||previous.ended()&&total==1&&!ended||standalone;
        count=reset?total:Math.max(0,total-previous.total());
        ungroupedCombos.put(giftGroup,new ComboProgress(reset?total:Math.max(previous.total(),total),received,ended||!reset&&previous.ended()));trim(ungroupedCombos,2000);
      }
      if (count == 0) return;
      gifts += count;
    } else if (type.equals("chat")) comments++; else members++;
    long timestamp = event.path("ts").asLong(System.currentTimeMillis());
    CaptureEvent row = new CaptureEvent(key, sourceRoom, type, timestamp, userId, nickname,
      clean(data.path("content").asText(), 2000), clean(data.path("giftName").asText("未知礼物"), 100), count, total);
    events.addLast(row); while (events.size() > MAX_EVENTS) events.removeFirst();
    lastEventAt = System.currentTimeMillis();
    room.captureEvent(row); publishStatus();
    // Keep every comment in the capture feed, while LiveRoom owns speech dedupe,
    // per-user throttling, queue bounds, asynchronous DeepSeek generation and TTS.
    // Reconnected collectors may replay history; only fresh events generate speech.
    if (type.equals("chat") && timestamp >= lastEventAt - 45000 && timestamp <= lastEventAt + 10000
        && !InputFilter.clean(row.content(), 160).isBlank()) {
      room.accept(new Protocol.LiveEvent("douyin-" + key, Protocol.EventType.COMMENT,
        row.userId(), row.nickname(), row.content(), "", 0, row.timestamp()));
    } else if (type.equals("member") && timestamp >= lastEventAt - 45000 && timestamp <= lastEventAt + 10000) {
      // LiveRoom merges arrivals for 1.6 seconds and builds a fixed welcome line.
      // USER_ENTER never requests an LLM; a real source ID enables direct cloud TTS.
      room.accept(new Protocol.LiveEvent("douyin-" + key, Protocol.EventType.USER_ENTER,
        row.userId(), row.nickname(), "", "", 0, row.timestamp()));
    } else if(type.equals("gift")&&timestamp>=lastEventAt-45000&&timestamp<=lastEventAt+10000){
      PendingGift previous=pendingGifts.get(giftGroup);
      pendingGifts.put(giftGroup,new PendingGift(row,count+(previous==null?0:previous.count()),previous==null?lastEventAt:previous.firstAt(),lastEventAt));
      trim(pendingGifts,2000);
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay=500)
  public void flushGifts(){flushGifts(System.currentTimeMillis());}
  synchronized void flushGifts(long now){
    var iterator=pendingGifts.entrySet().iterator();
    while(iterator.hasNext()){
      PendingGift gift=iterator.next().getValue();
      if(now-gift.lastAt()<1600&&now-gift.firstAt()<10000)continue;
      iterator.remove();CaptureEvent row=gift.row();
      room.accept(new Protocol.LiveEvent("douyin-gift-"+row.id(),Protocol.EventType.GIFT,row.userId(),row.nickname(),"",row.giftName(),(int)Math.min(Integer.MAX_VALUE,gift.count()),row.timestamp()));
    }
  }

  private static String clean(String text, int limit) {
    String result = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
    return result.substring(0, Math.min(result.length(), limit));
  }
  private static void trim(LinkedHashMap<?, ?> map, int max) { while (map.size() > max) map.remove(map.keySet().iterator().next()); }
  private void publishStatus() { room.captureChatStatus("douyin",roomId,state,CaptureChatMetrics.normalWorker("douyin",worker)); room.captureStatus(summary()); }
  private Map<String, Object> summary() {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("platform", "douyin");
    s.put("mode", "lightweight"); s.put("roomId", roomId); s.put("state", state); s.put("message", message);
    s.put("hasLogin", hasLogin); s.put("comments", comments); s.put("gifts", gifts); s.put("members", members);
    s.put("lastEventAt", lastEventAt); s.put("frames", frames); s.put("attempts", attempts); s.put("capacity", MAX_EVENTS);
    s.put("loginState", loginState); s.put("loginMessage", loginMessage);
    s.put("commentReplies", true);
    s.put("entryWelcomes", true);
    s.put("giftThanks", true);
    return s;
  }
  public synchronized Map<String, Object> snapshot() {
    Map<String, Object> s = summary(); s.put("events", List.copyOf(events)); return s;
  }
  public synchronized Map<String, Object> disconnect() {
    pendingGifts.clear();
    stopLoginProcess(); loginState = "idle"; loginMessage = "";
    stopProcess(); state = "stopped"; message = "采集已停止"; hasLogin = false; publishStatus(); return snapshot();
  }
  public synchronized Map<String, Object> clear() {
    // Preserve platform dedupe and combo state so replayed frames cannot be counted again.
    events.clear(); comments = gifts = members = lastEventAt = 0;
    room.captureReset(); publishStatus(); return snapshot();
  }
  private void clearInternal() { events.clear(); seen.clear(); combos.clear();ungroupedCombos.clear();pendingGifts.clear(); comments = gifts = members = lastEventAt = 0; room.captureReset(); }
  private void stopProcess() {
    Process old = process; process = null;
    if (old != null) {
      old.destroy();
      reader.submit(() -> { try { if (!old.waitFor(2, TimeUnit.SECONDS)) old.destroyForcibly(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); old.destroyForcibly(); } });
    }
  }
  private void stopLoginProcess() {
    Process old = loginProcess; loginProcess = null;
    if (old != null) {
      old.destroy();
      reader.submit(() -> { try { if (!old.waitFor(5, TimeUnit.SECONDS)) old.destroyForcibly(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); old.destroyForcibly(); } });
    }
  }
  @PreDestroy public synchronized void shutdown() { stopLoginProcess(); stopProcess(); reader.shutdown(); }
}
