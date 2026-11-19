package live.dafeiyu;

import com.fasterxml.jackson.databind.*;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit providers. A search result is not a playable source; there is no silent NetEase fallback. */
@Service @Primary
public final class ConfiguredMusicCatalog implements MusicCatalog {
  private final String provider,baseUrl;private final Path directory;private final ObjectMapper json;private final NeteaseMusicCatalog legacy;
  private final AudioTargetPolicy targets;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  private final Map<String,Resource> resources=new ConcurrentHashMap<>();
  record Resolved(URI url,Map<String,String> headers,String mime,long expiresAt){}
  static final class Resource {final Resolved resolved;final long expiresAt;Resource(Resolved resolved){this.resolved=resolved;this.expiresAt=Math.min(resolved.expiresAt(),System.currentTimeMillis()+90000);}}
  public ConfiguredMusicCatalog(Environment env,ObjectMapper json,NeteaseMusicCatalog legacy) {
    this.json=json;this.legacy=legacy;provider=env.getProperty("music.provider","local").strip().toLowerCase(Locale.ROOT);
    directory=Path.of(env.getProperty("music.local-dir","../defaultBackgroundMusic")).toAbsolutePath().normalize();
    baseUrl=env.getProperty("music.gateway-url","").replaceAll("/+$","");
    targets=new AudioTargetPolicy(env,baseUrl);
    if(!Set.of("local","http","awoo","qq-awoo","netease").contains(provider))throw new IllegalArgumentException("Unsupported music.provider");
    if(provider.equals("http")&&!baseUrl.isBlank())loopbackBase(baseUrl);
  }
  public Map<String,Object> providerStatus(){return Map.of("provider",provider,"configured",!provider.equals("netease")&&(!provider.equals("http")||!baseUrl.isBlank()),"controlAvailable",!Set.of("awoo","netease").contains(provider),"notice",provider.equals("netease")?"网易音源已停用，请选择本地或正常非网易音源":provider.equals("awoo")?"嗷呜外部接口只读，尚未接入点歌控制":provider.equals("local")?"从本地曲库搜索；不会自动使用网易":provider.equals("qq-awoo")?"QQ正常点歌入口；试听和整曲权限以客户端为准":"按已配置音源搜索");}
  public Song find(String query){return find(query,"");}
  public Song find(String query,String artist) {
    if(provider.equals("qq-awoo"))throw new IllegalArgumentException("QQ原生点歌须经过正常桥接入口，不生成网页音频地址");
    if(provider.equals("awoo"))throw new IllegalArgumentException("嗷呜外部接口只读，尚未接入点歌控制；没有改变播放器");
    if(provider.equals("netease"))throw new IllegalArgumentException("网易音源已停用，请选择本地或正常非网易音源");
    if(provider.equals("local"))return local(query,artist);
    if(baseUrl.isBlank())throw new IllegalArgumentException("尚未配置HTTP音源服务，没有加入队列");
    try {
      JsonNode result=get("/v1/search?query="+enc(query)+"&artist="+enc(artist)+"&limit=5");
      List<JsonNode> items=new ArrayList<>();result.path("items").forEach(items::add);
      items.removeIf(n->n.path("id").asText().isBlank()||n.path("title").asText().isBlank()||n.path("provider").asText().isBlank()||"netease".equalsIgnoreCase(n.path("provider").asText()));
      if(items.isEmpty())throw new IllegalArgumentException("没有找到可用的非网易歌曲，试试歌名和歌手");
      Map<String,JsonNode> matches=new LinkedHashMap<>();
      for(JsonNode item:items){
        String title=normalize(clean(item,"title",120)),singer=normalize(clean(item,"artist",120)),wanted=normalize(query);
        boolean titleMatches=title.equals(wanted)||artist.isBlank()&&(title+singer).equals(wanted)||artist.isBlank()&&(singer+title).equals(wanted);
        boolean artistMatches=artist.isBlank()||Arrays.stream(clean(item,"artist",120).split("[/、,，;&]")).map(ConfiguredMusicCatalog::normalize).anyMatch(normalize(artist)::equals);
        if(titleMatches&&artistMatches)matches.put(clean(item,"provider",40)+":"+clean(item,"id",120),item);
      }
      if(matches.isEmpty())throw new IllegalArgumentException("搜索结果与歌名或歌手不一致，请补充准确歌名和歌手");
      if(matches.size()>1)throw new IllegalArgumentException("找到多个同名版本，请补充歌手或版本，没有加入队列");
      JsonNode item=matches.values().iterator().next();String id=clean(item,"id",120),source=clean(item,"provider",40);
      Resolved resolved=resolve(source,id);probe(resolved);
      String token=issue(resolved);
      return new Song(source+":"+id,clean(item,"title",120),clean(item,"artist",120),"","/api/music/stream/"+token,Math.max(0,item.path("durationMs").asLong()),source);
    }catch(IllegalArgumentException failure){throw failure;}catch(Exception failure){throw new IllegalArgumentException("音源连接失败，没有加入队列");}
  }
  private Song local(String query,String artist) {
    String key=normalize(query);if(key.isBlank())throw new IllegalArgumentException("请输入歌曲名");
    try {
      if(!Files.isDirectory(directory))throw new IllegalArgumentException("本地曲库为空，尚未配置在线音源");
      Path real=directory.toRealPath();List<Path> matches;
      try(var entries=Files.list(real)){
        matches=entries.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(mp3|wav|ogg|m4a)"))
          .filter(p->normalize(p.getFileName().toString().replaceFirst("\\.[^.]+$","")).contains(key)&&normalize(p.getFileName().toString()).contains(normalize(artist))).sorted().toList();
      }
      if(matches.isEmpty())throw new IllegalArgumentException("本地曲库未找到这首歌；在线音源尚未配置");
      if(matches.size()>1)throw new IllegalArgumentException("本地曲库有多个同名版本，请补充歌手或版本");
      Path file=matches.getFirst().toRealPath();if(!file.getParent().equals(real))throw new IllegalArgumentException("本地歌曲不可读取");
      String name=file.getFileName().toString(),id=ProviderStore.hash(name+"|"+Files.size(file)+"|"+Files.getLastModifiedTime(file).toMillis());
      return new Song("local-"+id,name.replaceFirst("\\.[^.]+$",""),"本地曲库","","/api/music/local/"+id,0,"local");
    }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("本地曲库不可读取");}
  }
  private static String normalize(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[\\s《》]+","");}
  private JsonNode get(String path)throws Exception {
    HttpResponse<String> response=http.send(HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(8)).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if(response.statusCode()!=200||response.body().length()>262144)throw new IllegalArgumentException("音源接口暂不可用");
    return json.readTree(response.body());
  }
  private Resolved resolve(String source,String id)throws Exception {
    JsonNode data=get("/v1/resolve?provider="+enc(source)+"&id="+enc(id));URI uri=URI.create(data.path("url").asText());
    targets.validate(uri);
    Map<String,String> headers=new LinkedHashMap<>();
    if(data.path("headers").isObject())data.path("headers").fields().forEachRemaining(e->{String k=e.getKey(),v=e.getValue().asText();if(Set.of("user-agent","referer","authorization","cookie","accept").contains(k.toLowerCase(Locale.ROOT))&&!v.contains("\r")&&!v.contains("\n"))headers.put(k,v);});
    long expiry=data.path("expiresAt").asLong(System.currentTimeMillis()+240000);if(expiry<=System.currentTimeMillis())throw new IllegalArgumentException("音源地址已经过期");
    return new Resolved(uri,Map.copyOf(headers),data.path("mime").asText("audio/mpeg"),expiry);
  }
  private HttpRequest request(Resolved resolved,String range) {
    targets.validate(resolved.url());
    var builder=HttpRequest.newBuilder(resolved.url()).timeout(Duration.ofSeconds(20)).GET();
    resolved.headers().forEach(builder::header);if(range!=null)builder.header("Range",range);return builder.build();
  }
  private void probe(Resolved resolved)throws Exception {
    var response=http.send(request(resolved,"bytes=0-4095"),HttpResponse.BodyHandlers.ofInputStream());
    var deadline=MusicStreamLimits.closeAfter(response.body(),3000);
    try(InputStream input=response.body()){
      String mime=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
      byte[] bytes=input.readNBytes(4096);
      if(!Set.of(200,206).contains(response.statusCode())||!(mime.startsWith("audio/")||mime.startsWith("application/ogg"))||bytes.length<16)throw new IllegalArgumentException("音源无法加载，没有加入队列");
      String start=new String(bytes,0,Math.min(4,bytes.length),StandardCharsets.US_ASCII);
      boolean header=start.startsWith("ID3")||start.equals("RIFF")||start.equals("OggS")||start.equals("fLaC")||new String(bytes,4,4,StandardCharsets.US_ASCII).equals("ftyp")||((bytes[0]&255)==255&&(bytes[1]&224)==224);
      if(!header)throw new IllegalArgumentException("音源未返回可识别音频，没有加入队列");
    }finally{deadline.cancel(false);}
  }
  public HttpResponse<InputStream> openStream(String token,String range)throws Exception {
    Resource resource=resources.get(token);if(resource==null)throw new ProviderException("MUSIC_STREAM_NOT_FOUND",404);
    if(resource.expiresAt<=System.currentTimeMillis())throw new ProviderException("MUSIC_STREAM_EXPIRED",410);
    if(range!=null&&!range.matches("bytes=\\d+-\\d*"))throw new ProviderException("INVALID_MUSIC_RANGE",416);
    Resolved current=resource.resolved;
    var response=http.send(request(current,range),HttpResponse.BodyHandlers.ofInputStream());
    if(!Set.of(200,206).contains(response.statusCode())||response.headers().firstValueAsLong("Content-Length").orElse(0)>67108864||!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).matches("(audio/.*|application/ogg.*)")){response.body().close();throw new ProviderException("MUSIC_STREAM_UNAVAILABLE",502);}
    return response;
  }
  private synchronized String issue(Resolved resolved){resources.entrySet().removeIf(e->e.getValue().expiresAt<=System.currentTimeMillis());if(resources.size()>=128)throw new IllegalArgumentException("同时解析的歌曲过多，请稍后再试");String token=UUID.randomUUID().toString();resources.put(token,new Resource(resolved));return token;}
  public Song prepare(Song song){
    if("local".equals(song.source())&&song.songId().matches("local-[a-f0-9]{64}")&&song.audioUrl().equals("/api/music/local/"+song.songId().substring(6)))return song;
    if(!provider.equals("http"))return song;
    try{
      String token=song.audioUrl().substring(song.audioUrl().lastIndexOf('/')+1);Resource cached=resources.get(token);
      if(cached!=null&&cached.expiresAt>System.currentTimeMillis()+10000)return song;
      String source=song.source(),prefix=source+":";
      if(!song.songId().startsWith(prefix))throw new IllegalArgumentException();
      Resolved resolved=resolve(source,song.songId().substring(prefix.length()));probe(resolved);
      return new Song(song.songId(),song.title(),song.artist(),song.songUrl(),"/api/music/stream/"+issue(resolved),song.durationMs(),source);
    }catch(Exception failed){throw new IllegalArgumentException("播放前无法重新取得可用音源");}
  }
  private static void loopbackBase(String base){try{URI u=URI.create(base);if(!"http".equals(u.getScheme())||!Set.of("127.0.0.1","localhost","[::1]","::1").contains(u.getHost())||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new IllegalArgumentException();}catch(Exception invalid){throw new IllegalArgumentException("music.gateway-url must be an explicit loopback HTTP service");}}
  private static String clean(JsonNode node,String field,int limit){return InputFilter.clean(node.path(field).asText(),limit);}
  private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}

