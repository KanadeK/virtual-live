package live.dafeiyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** One authenticated Bilibili collector, normalized into the existing room queue. */
@Service
public class BilibiliCaptureService {
  public record CaptureEvent(String platform, String id, String roomId, String type, long timestamp,
      String userId, String nickname, String content, String giftName, long giftCount, long comboTotal) {}
  private record GiftProgress(long increments, long reported, long applied, long timestamp) {}
  private record GiftWindow(String key, long lastAt, boolean grouped) {}
  private record PendingGift(CaptureEvent row, long count, long firstAt, long lastAt) {}
  private final ObjectMapper json;
  private final LiveRoom room;
  private final MusicService music;
  private static final Pattern SONG_COMMAND = Pattern.compile("(?U)^\\s*点歌(?:\\s+|[,，:：])([\\s\\S]*)$");
  private final ThreadPoolExecutor songRequests = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(8), r -> Thread.ofPlatform().daemon().name("bilibili-song").unstarted(r));
  private long songGeneration;
  private record SemanticSource(long generation,CaptureEvent event){}
  private final LinkedHashMap<String,SemanticSource> semanticSources=new LinkedHashMap<>();
  private final String node;
  private final Path worker;
  private final ExecutorService reader = Executors.newCachedThreadPool(r -> Thread.ofPlatform().daemon().name("bilibili-capture").unstarted(r));
  private final Deque<CaptureEvent> events = new ArrayDeque<>();
  private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
  private final LinkedHashMap<String, GiftProgress> combos = new LinkedHashMap<>();
  private final LinkedHashMap<String, GiftWindow> giftWindows = new LinkedHashMap<>();
  private final LinkedHashMap<String, PendingGift> pendingGifts = new LinkedHashMap<>();
  private Process process, loginProcess;
  private String inputRoomId = "", roomId = "", state = "stopped", message = "尚未连接B站直播间";
  private String loginState = "idle", loginMessage = "", errorCode = "";
  private boolean hasLogin;
  private int liveStatus, attempts;
  private long comments, gifts, members, frames, lastEventAt;

  public BilibiliCaptureService(ObjectMapper json, LiveRoom room, MusicService music,
      @Value("${bilibili.node:node}") String node,
      @Value("${bilibili.worker:../collector/dist/bilibili/worker.js}") String worker) {
    this.json = json; this.room = room; this.music = music; this.node = node; this.worker = Path.of(worker).toAbsolutePath().normalize();
  }
  static String parseRoom(String input) {
    String value = input == null ? "" : input.trim();
    if (value.matches("[1-9]\\d{0,29}")) return value;
    try {
      URI uri = URI.create(value);
      if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
          && "live.bilibili.com".equalsIgnoreCase(uri.getHost()) && uri.getUserInfo() == null && uri.getPort() == -1
          && uri.getRawPath().matches("/[1-9]\\d{0,29}/?")) return uri.getPath().replace("/", "");
    } catch (IllegalArgumentException ignored) {}
    throw new IllegalArgumentException("请输入B站房间号或 live.bilibili.com 直播链接");
  }
  void validateConnect(String input, String cookie) {
    parseRoom(input);
    if (cookie != null && (cookie.length() > 16000 || cookie.indexOf('\r') >= 0 || cookie.indexOf('\n') >= 0 || cookie.indexOf('\0') >= 0))
      throw new IllegalArgumentException("Cookie格式不正确，请填写单行Cookie请求头的值");
    if (!Files.isRegularFile(worker)) throw new IllegalArgumentException("B站采集组件尚未构建，请在collector目录执行npm ci和npm run build");
  }
  void validateLogin(String input) {
    parseRoom(input);
    if (!Files.isRegularFile(worker.resolveSibling("login.js"))) throw new IllegalArgumentException("B站登录组件尚未构建，请在collector目录执行npm run build");
  }
  public synchronized Map<String, Object> connect(String input, String cookieInput) {
    validateConnect(input, cookieInput);
    String next = parseRoom(input), cookie = cookieInput == null ? "" : cookieInput.trim();
    stopLoginProcess(); stopProcess(); pendingGifts.clear();
    if (!next.equals(inputRoomId) && !next.equals(roomId)) clearDisplay();
    inputRoomId = next; roomId = next; errorCode = ""; liveStatus = 0; hasLogin = false; frames = 0; attempts = 0;
    if (Set.of("opening", "waiting").contains(loginState)) { loginState = "idle"; loginMessage = ""; }
    state = "connecting"; message = "正在获取B站房间信息及正常弹幕凭证";
    try {
      Process child = startProcess(worker, Map.of("platform", "bilibili", "roomId", next, "cookie", cookie));
      process = child; reader.submit(() -> read(child, false, next));
    } catch (IOException e) { stopProcess(); state = "error"; errorCode = "WORKER_UNAVAILABLE"; message = "无法启动B站采集组件，请检查Node.js及collector构建"; }
    publishStatus(); return snapshot();
  }
  public synchronized Map<String, Object> login(String input) {
    validateLogin(input);
    String loginRoom = parseRoom(input);
    stopLoginProcess(); stopProcess(); pendingGifts.clear(); hasLogin = false;
    state = "stopped"; errorCode = ""; message = "等待B站登录完成";
    loginState = "opening"; loginMessage = "正在打开独立B站登录窗口";
    try {
      Process child = startProcess(worker.resolveSibling("login.js"), Map.of("platform", "bilibili", "roomId", loginRoom));
      loginProcess = child; reader.submit(() -> read(child, true, loginRoom));
    } catch (IOException e) { stopLoginProcess(); loginState = "error"; loginMessage = "无法启动登录窗口，请检查Chrome及Node.js"; }
    publishStatus(); return snapshot();
  }
  private Process startProcess(Path script, Map<String, String> config) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(node, script.toString()).directory(script.getParent().toFile());
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().remove("BILIBILI_COOKIE");
    Process child = builder.start();
    try {
      child.getOutputStream().write((json.writeValueAsString(config) + "\n").getBytes(StandardCharsets.UTF_8));
      child.getOutputStream().flush(); return child;
    } catch (IOException e) { child.destroy(); throw e; }
  }
  private void read(Process child, boolean login, String loginRoom) {
    try (BufferedReader lines = child.inputReader(StandardCharsets.UTF_8)) {
      String line;
      while ((line = lines.readLine()) != null) {
        if (line.length() > 100000) continue;
        try {
          JsonNode wire = json.readTree(line);
          synchronized (this) {
            if ((login ? loginProcess : process) != child) return;
            if (!"bilibili".equals(wire.path("platform").asText())) continue;
            if (login) readLogin(wire, loginRoom);
            else if ("resolved".equals(wire.path("kind").asText()) && wire.path("roomId").asText().matches("[1-9]\\d{0,29}")) {
              roomId = wire.path("roomId").asText(); liveStatus = wire.path("liveStatus").asInt();
              hasLogin = wire.path("hasLogin").asBoolean(hasLogin); publishStatus();
            } else if ("status".equals(wire.path("kind").asText())) {
              String next = wire.path("state").asText();
              if (!Set.of("connecting", "live", "closed", "error", "reconnecting").contains(next)) continue;
              state = next; message = clean(wire.path("message").asText(), 300);
              errorCode = clean(wire.path("errorCode").asText(), 60);
              hasLogin = wire.path("hasLogin").asBoolean(hasLogin);
              frames = Math.max(frames, wire.path("frameCount").asLong(frames)); attempts = wire.path("attempts").asInt(attempts);
              publishStatus();
            } else if ("event".equals(wire.path("kind").asText()) && "live".equals(state)) {
              JsonNode event=wire.path("event");
              if (CaptureChatMetrics.normalWorker("bilibili",worker) && "bilibili".equals(event.path("platform").asText()) && "live".equals(state) && "chat".equals(event.path("type").asText()))
                room.captureChat("bilibili",event.path("roomId").asText(),event.path("id").asText(),event.path("user").path("id").asText(),event.path("data").path("content").asText(),event.path("ts").asLong(0));
              ingest(event,CaptureChatMetrics.normalWorker("bilibili",worker));
            }
          }
        } catch (IOException | IllegalArgumentException ignored) { /* Malformed subprocess messages are ignored. */ }
      }
    } catch (IOException ignored) { /* Stop closes the pipe. */ }
    synchronized (this) {
      if (login && loginProcess == child) {
        loginProcess = null;
        if (Set.of("opening", "waiting").contains(loginState)) { loginState = "error"; loginMessage = "B站登录窗口已退出，请重试"; }
        publishStatus();
      } else if (!login && process == child) {
        process = null;
        if (!Set.of("closed", "error").contains(state)) { state = "error"; errorCode = "WORKER_EXITED"; message = "B站采集进程已退出，请重新连接"; }
        publishStatus();
      }
    }
  }
  private void readLogin(JsonNode wire, String loginRoom) {
    if ("credentials".equals(wire.path("kind").asText())) {
      Process old = loginProcess; loginProcess = null;
      loginState = "success"; loginMessage = "已取得B站登录态，正在自动鉴权连接";
      connect(loginRoom, wire.path("cookie").asText());
      destroy(old);
    } else if ("status".equals(wire.path("kind").asText())) {
      String next = wire.path("state").asText();
      if (Set.of("opening", "waiting", "expired", "cancelled", "error").contains(next)) {
        loginState = next; loginMessage = clean(wire.path("message").asText(), 300); publishStatus();
      }
    }
  }
  // Package-visible for protocol tests. Production only invokes this for the current live worker.
  synchronized void ingest(JsonNode event) { ingest(event,false); }
  private void ingest(JsonNode event,boolean metricOrigin) {
    String type = event.path("type").asText(), sourceRoom = event.path("roomId").asText();
    if (!"bilibili".equals(event.path("platform").asText()) || !sourceRoom.equals(roomId) || !Set.of("chat", "member", "gift").contains(type)) return;
    String sourceId = clean(event.path("id").asText(), 200);
    String userId = event.path("user").path("id").asText();
    if (sourceId.isBlank() || !userId.matches("[1-9]\\d{0,29}")) return;
    String key = "bilibili:" + sourceRoom + ":" + type + ":" + sourceId;
    if (seen.putIfAbsent(key, true) != null) return;
    trim(seen, 5000);
    JsonNode data = event.path("data");
    long received = System.currentTimeMillis(), timestamp = event.path("ts").asLong(0), count = 0, total = 0;
    String giftGroup = key;
    if ("gift".equals(type)) {
      String giftId = data.path("giftId").asText(), group = clean(data.path("groupId").asText(), 200);
      boolean cumulative = "total".equals(data.path("countMode").asText());
      if (!giftId.matches("\\d{1,30}")) return;
      String base = "bilibili:" + sourceRoom + ":" + userId + ":" + giftId;
      GiftWindow window = giftWindows.get(base);
      boolean recent = window != null && received - window.lastAt() <= 10000;
      // SEND_GIFT_V2 has transaction IDs but no combo ID. Correlate its increments
      // with the same user's/gift's active cumulative group inside a bounded window.
      giftGroup = !group.isBlank() ? base + ":group:" + group
        : recent ? window.key() : base + ":inferred:" + sourceId;
      GiftProgress previous = combos.get(giftGroup);
      String metricAlias="";
      if (previous == null && !group.isBlank() && recent && !window.grouped()) {
        previous = combos.remove(window.key());
        if(previous!=null)metricAlias=window.key();
      }
      giftWindows.put(base, new GiftWindow(giftGroup, received, !group.isBlank() || recent && window.grouped())); trim(giftWindows, 5000);
      // A timestamp-less COMBO_SEND may finish only a known fresh group. Unknown replay
      // summaries are retained for dedupe but must not manufacture fresh activity.
      if (timestamp == 0 && cumulative && previous != null) timestamp = previous.timestamp();
      long increments = previous == null ? 0 : previous.increments();
      if (!cumulative) increments = Math.min(1000000, increments + bounded(data.path("num").asLong()));
      long reported = Math.max(previous == null ? 0 : previous.reported(), bounded(data.path("repeatCount").asLong()));
      total = Math.max(increments, reported);
      count = Math.max(0, total - (previous == null ? 0 : previous.applied()));
      combos.put(giftGroup, new GiftProgress(increments, reported, total, timestamp)); trim(combos, 5000);
      if(metricOrigin)room.captureGift("bilibili",sourceRoom,key,count,data.path("countMode").asText(),data.path("num").asLong(0),data.path("coinType").asText(),data.path("totalCoin").isNull()?null:data.path("totalCoin").asText(),timestamp,giftGroup,metricAlias,bounded(data.path("repeatCount").asLong()));
      if (count == 0) return;
      gifts += count;
    } else if ("chat".equals(type)) comments++; else members++;
    String nickname = clean(event.path("user").path("nickname").asText(), 80);
    CaptureEvent row = new CaptureEvent("bilibili", key, sourceRoom, type, timestamp, userId,
      nickname.isBlank() ? "匿名观众" : nickname, clean(data.path("content").asText(), 2000),
      clean(data.path("giftName").asText("礼物"), 100), count, total);
    events.addLast(row); while (events.size() > 500) events.removeFirst(); lastEventAt = received;
    room.captureEvent(row); publishStatus();
    if (timestamp < received - 45000 || timestamp > received + 10000) return;
    if ("chat".equals(type) && !InputFilter.clean(row.content(), 160).isBlank()) {
      Protocol.LiveEvent comment = new Protocol.LiveEvent("bilibili-" + key, Protocol.EventType.COMMENT, userId, row.nickname(), row.content(), "", 0, timestamp);
      String command = songCommand(row.content());
      if (command == null) {
        semanticSources.put(comment.eventId(),new SemanticSource(songGeneration,row));
        while(semanticSources.size()>3000)semanticSources.remove(semanticSources.keySet().iterator().next());
        room.accept(comment);
      }
      else {
        Map<String, Object> admitted = room.acceptMusicCommand(comment);
        if (Boolean.TRUE.equals(admitted.get("accepted"))) submitSong(row, command);
        else if ("rate_limited".equals(admitted.get("reason"))) music.requestNotice("B站点歌过于频繁，同一观众请间隔4秒");
      }
    } else if ("member".equals(type)) {
      room.accept(new Protocol.LiveEvent("bilibili-" + key, Protocol.EventType.USER_ENTER, userId, row.nickname(), "", "", 0, timestamp));
    } else if ("gift".equals(type)) {
      String pendingKey = "bilibili:" + sourceRoom + ":" + userId + ":" + data.path("giftId").asText();
      PendingGift old = pendingGifts.get(pendingKey);
      pendingGifts.put(pendingKey, new PendingGift(row, count + (old == null ? 0 : old.count()), old == null ? received : old.firstAt(), received));
      trim(pendingGifts, 2000);
    }
  }
  private static String songCommand(String content) {
    var match = SONG_COMMAND.matcher(content);
    if (!match.matches()) return null;
    String query = InputFilter.clean(match.group(1).replaceFirst("(?U)^\\s*[:：]?\\s*", "").replaceAll("(?U)\\s+", " "), 120);
    return query.isBlank() ? null : "点歌 " + query;
  }
  String semanticRequest(Protocol.LiveEvent event,AiDecision decision,java.util.function.BooleanSupplier active) {
    SemanticSource source;
    synchronized(this){
      source=semanticSources.get(event.eventId());
      if(source==null||!currentSongSource(source.generation(),source.event())||!active.getAsBoolean())return "这条点歌已过期，没有加入队列。";
    }
    NativeMusicBridge bridge=room.nativeMusic();
    if(bridge!=null&&bridge.enabled()){
      synchronized(this){if(!currentSongSource(source.generation(),source.event()))return "直播来源已变化，这次没有加入队列。";bridge.source("bilibili-"+source.generation());}
      try{
        var row=source.event();var ticket=bridge.prepare(event.eventId(),row.timestamp(),"bilibili",row.userId(),row.nickname(),decision,active,room.taskIdForEvent(event.eventId()));
        synchronized(this){if(!currentSongSource(source.generation(),row)){bridge.cancelPrepared(ticket);return "直播来源已变化，这次没有加入队列。";}return bridge.commit(ticket);}
      }catch(ProviderException unavailable){return NativeSongFailure.message(unavailable,"QQ正常点歌尚未确认，这次没有报告播放成功。");}
    }
    try {
      MusicService.Selection song=music.searchDecision(decision);
      synchronized(this){
        if(!currentSongSource(source.generation(),source.event())||!active.getAsBoolean())return "直播来源已变化，这次没有加入队列。";
        return music.semanticEnqueue(song,active);
      }
    }catch(IllegalArgumentException failure){return "点歌没有成功："+InputFilter.clean(failure.getMessage(),100);}
  }
  // Called under the capture lock. The single bounded worker never performs network I/O while holding it.
  private void submitSong(CaptureEvent row, String command) {
    long generation = songGeneration;
    try { songRequests.execute(() -> {
      synchronized (this) { if (!currentSongSource(generation, row)) return; }
      NativeMusicBridge bridge=room.nativeMusic();
      if(bridge!=null&&bridge.enabled()){
        synchronized(this){if(!currentSongSource(generation,row))return;bridge.source("bilibili-"+generation);}
        try{
          String query=command.replaceFirst("^点歌\\s+","");var ticket=bridge.prepare("bilibili-comment-"+row.id(),row.timestamp(),"bilibili",row.userId(),row.nickname(),new AiDecision("music_request",InputFilter.clean(query,60),"",""),()->true,"");
          synchronized(this){if(currentSongSource(generation,row))music.requestNotice(bridge.commit(ticket));else bridge.cancelPrepared(ticket);}
        }catch(ProviderException unavailable){synchronized(this){if(currentSongSource(generation,row))music.requestNotice(NativeSongFailure.message(unavailable,"QQ正常点歌尚未确认，请稍后重试"));}}
        return;
      }
      try {
        MusicService.Selection song = music.searchRequest(command);
        synchronized (this) { if (currentSongSource(generation, row)) music.enqueueRequest(song); }
      } catch (IllegalArgumentException failure) {
        synchronized (this) { if (currentSongSource(generation, row)) music.requestNotice("B站点歌失败：" + InputFilter.clean(failure.getMessage(), 180)); }
      } catch (RuntimeException failure) {
        synchronized (this) { if (currentSongSource(generation, row)) music.requestNotice("B站点歌搜索暂不可用，请稍后重试"); }
      }
    }); } catch (RejectedExecutionException full) { music.requestNotice("B站点歌请求较多，请稍后重试"); }
  }
  private boolean currentSongSource(long generation, CaptureEvent row) {
    long now = System.currentTimeMillis();
    return generation == songGeneration && "live".equals(state) && row.roomId().equals(roomId)
      && row.timestamp() >= now - 45000 && row.timestamp() <= now + 10000;
  }
  private static long bounded(long count) { return Math.max(0, Math.min(1000000, count)); }
  @Scheduled(fixedDelay = 500) public void flushGifts() { flushGifts(System.currentTimeMillis()); }
  synchronized void flushGifts(long now) {
    var iterator = pendingGifts.entrySet().iterator();
    while (iterator.hasNext()) {
      PendingGift gift = iterator.next().getValue();
      if (now - gift.lastAt() < 1600 && now - gift.firstAt() < 10000) continue;
      iterator.remove(); CaptureEvent row = gift.row();
      room.accept(new Protocol.LiveEvent("bilibili-gift-" + row.id(), Protocol.EventType.GIFT, row.userId(), row.nickname(), "", row.giftName(), (int)Math.min(1000000, gift.count()), row.timestamp()));
    }
  }
  private static String clean(String value, int max) {
    String result = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", ""); return result.substring(0, Math.min(max, result.length()));
  }
  private static void trim(LinkedHashMap<?, ?> values, int max) { while (values.size() > max) values.remove(values.keySet().iterator().next()); }
  private Map<String, Object> summary() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("platform", "bilibili"); value.put("mode", "bilibili-web"); value.put("inputRoomId", inputRoomId);
    value.put("roomId", roomId); value.put("state", state); value.put("message", message); value.put("errorCode", errorCode);
    value.put("requiresLogin", "LOGIN_REQUIRED".equals(errorCode)); value.put("hasLogin", hasLogin); value.put("liveStatus", liveStatus);
    value.put("comments", comments); value.put("gifts", gifts); value.put("members", members); value.put("lastEventAt", lastEventAt);
    value.put("frames", frames); value.put("attempts", attempts); value.put("capacity", 500);
    value.put("loginState", loginState); value.put("loginMessage", loginMessage);
    value.put("commentReplies", true); value.put("entryWelcomes", true); value.put("giftThanks", true); return value;
  }
  private void publishStatus() { room.captureChatStatus("bilibili",roomId,state,CaptureChatMetrics.normalWorker("bilibili",worker)); NativeMusicBridge bridge=room.nativeMusic();if(bridge!=null&&bridge.enabled()&&"live".equals(state))bridge.source("bilibili-"+songGeneration);room.captureStatus(summary()); }
  public synchronized Map<String, Object> snapshot() { Map<String,Object> value = summary(); value.put("events", List.copyOf(events)); return value; }
  public synchronized Map<String, Object> disconnect() {
    stopLoginProcess(); stopProcess(); pendingGifts.clear(); hasLogin = false; loginState = "idle"; loginMessage = "";
    state = "stopped"; message = "B站采集已停止"; errorCode = ""; publishStatus(); return snapshot();
  }
  public synchronized Map<String, Object> cancelLogin() {
    stopLoginProcess(); loginState = "idle"; loginMessage = "登录已取消"; publishStatus(); return snapshot();
  }
  private void clearDisplay() { events.clear(); comments = gifts = members = lastEventAt = 0; room.captureReset("bilibili"); }
  public synchronized Map<String, Object> clear() { clearDisplay(); publishStatus(); return snapshot(); }
  private void destroy(Process old) {
    if (old == null) return;
    // Closing stdin lets the login helper close Chrome gracefully, including on Windows.
    try { old.getOutputStream().close(); } catch (IOException ignored) {}
    reader.submit(() -> { try { if (!old.waitFor(5, TimeUnit.SECONDS)) old.destroyForcibly(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); old.destroyForcibly(); } });
  }
  private void stopProcess() { songGeneration++; semanticSources.clear(); songRequests.getQueue().clear();NativeMusicBridge bridge=room.nativeMusic();if(bridge!=null&&bridge.enabled())bridge.source("bilibili-stopped-"+songGeneration); Process old = process; process = null; destroy(old); }
  private void stopLoginProcess() { Process old = loginProcess; loginProcess = null; destroy(old); }
  @PreDestroy public synchronized void shutdown() { stopLoginProcess(); stopProcess(); songRequests.shutdownNow(); reader.shutdown(); }
}
