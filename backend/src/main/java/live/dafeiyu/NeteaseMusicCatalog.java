package live.dafeiyu;

import com.fasterxml.jackson.databind.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Public website search and public outer-link playback. No login or paid-song unlocking. */
@Service
public final class NeteaseMusicCatalog implements MusicCatalog {
  private final HttpClient http;private final ObjectMapper json;private final String searchUrl;
  private record Cached(long expires,Song song) {}
  private final Map<String,Cached> cache=new LinkedHashMap<>();
  public NeteaseMusicCatalog(IntegrationConfig integration,ObjectMapper json,Environment env){http=integration.http;this.json=json;searchUrl=env.getProperty("music.search-url","https://music.163.com/api/search/get");}
  public Song find(String query){
    synchronized(cache){Cached hit=cache.get(query);if(hit!=null&&hit.expires>System.currentTimeMillis())return hit.song;}
    try{
      String form="s="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&type=1&limit=10&offset=0";
      HttpRequest request=HttpRequest.newBuilder(URI.create(searchUrl)).timeout(Duration.ofSeconds(6)).header("User-Agent","Mozilla/5.0").header("Referer","https://music.163.com/").header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
      HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if(response.statusCode()!=200)throw new IllegalArgumentException("网易云搜索暂不可用，请稍后重试");
      JsonNode result=json.readTree(response.body());List<JsonNode> songs=new ArrayList<>();result.path("result").path("songs").forEach(songs::add);
      songs.removeIf(song->song.path("id").asLong()<=0||song.path("name").asText().isBlank());
      // Prefer an exact title; otherwise preserve NetEase's relevance ordering.
      songs.sort(Comparator.comparingInt(song->song.path("name").asText().equalsIgnoreCase(query)?0:1));
      if(songs.isEmpty())throw new IllegalArgumentException("没有找到这首歌，试试“歌名 歌手”");
      JsonNode node=songs.getFirst();String id=node.path("id").asText();List<String> artists=new ArrayList<>();node.path("artists").forEach(artist->artists.add(InputFilter.clean(artist.path("name").asText(),60)));
      Song song=new Song(id,InputFilter.clean(node.path("name").asText(),120),artists.isEmpty()?"未知歌手":String.join(" / ",artists),"https://music.163.com/#/song?id="+id,"https://music.163.com/song/media/outer/url?id="+id+".mp3",Math.max(0,node.path("duration").asLong()));
      synchronized(cache){cache.put(query,new Cached(System.currentTimeMillis()+3600000,song));while(cache.size()>128)cache.remove(cache.keySet().iterator().next());}
      return song;
    }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("网易云搜索连接失败，请稍后重试");}
  }
}
