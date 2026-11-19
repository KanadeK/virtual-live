package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Safe same-origin media selected only from the configured local background folder. */
@Component
public final class DefaultMusicPlaylist {
  private final MusicService music;
  private final Path directory;
  private final boolean enabled;
  private volatile Map<String,Path> files=Map.of();
  public DefaultMusicPlaylist(MusicService music,ObjectMapper json,Environment env) {
    this.music=music;directory=Path.of(env.getProperty("music.local-dir","../defaultBackgroundMusic")).toAbsolutePath().normalize();
    enabled=env.getProperty("music.local-enabled",Boolean.class,true);
  }
  @EventListener(ApplicationReadyEvent.class)
  public void initialize(){var songs=scan();if(enabled)music.localBackground(songs);}
  public Map<String,Object> add(){return music.localBackground(scan());}
  private synchronized List<MusicCatalog.Song> scan(){
    Map<String,Path> next=new LinkedHashMap<>();List<MusicCatalog.Song> songs=new ArrayList<>();
    try{
      if(Files.isDirectory(directory)){
        Path root=directory.toRealPath();
        try(var stream=Files.list(root)){
          for(Path candidate:stream.sorted().toList()){
            if(!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)||!supported(candidate))continue;
            Path path=candidate.toRealPath();if(!path.getParent().equals(root))continue;
            String name=path.getFileName().toString();String id=ProviderStore.hash(name+"|"+Files.size(path)+"|"+Files.getLastModifiedTime(path).toMillis());
            next.put(id,path);songs.add(new MusicCatalog.Song("local-"+id,name.replaceFirst("\\.[^.]+$",""),"本地背景音乐","","/api/music/local/"+id,0,"local"));
          }
        }
      }
    }catch(IOException failure){throw new IllegalArgumentException("本地背景音乐目录不可读取");}
    files=Map.copyOf(next);return List.copyOf(songs);
  }
  Resource audio(String id){
    if(id==null||!id.matches("[a-f0-9]{64}"))throw new ProviderException("LOCAL_MUSIC_NOT_FOUND",404);
    Path file=files.get(id);
    if(file==null){scan();file=files.get(id);}
    try{if(file==null||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().getParent().equals(directory.toRealPath()))throw new IOException();return new FileSystemResource(file);}
    catch(IOException failure){throw new ProviderException("LOCAL_MUSIC_NOT_FOUND",404);}
  }
  static String mediaType(Resource file){
    String name=Objects.requireNonNullElse(file.getFilename(),"").toLowerCase(Locale.ROOT);
    return name.endsWith(".wav")?"audio/wav":name.endsWith(".ogg")?"audio/ogg":name.endsWith(".m4a")?"audio/mp4":"audio/mpeg";
  }
  private static boolean supported(Path path){return path.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(mp3|wav|ogg|m4a)");}
}
