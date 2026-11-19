package live.dafeiyu;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public final class ProviderStore {
  private final Path root;
  ProviderStore(IntegrationConfig config){root=config.dataDir;}
  static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("SHA256 unavailable");}}
  Path path(String kind,String hash,String suffix){if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid cache id");return root.resolve(kind).resolve(hash+suffix);}
  Optional<byte[]> read(String kind,String hash,String suffix,long ttlMillis){
    Path path=path(kind,hash,suffix);
    try{if(!Files.isRegularFile(path)||System.currentTimeMillis()-Files.getLastModifiedTime(path).toMillis()>ttlMillis)return Optional.empty();return Optional.of(Files.readAllBytes(path));}
    catch(IOException e){return Optional.empty();}
  }
  synchronized void write(String kind,String hash,String suffix,byte[] bytes){
    Path target=path(kind,hash,suffix);
    try{Files.createDirectories(target.getParent());Path tmp=Files.createTempFile(target.getParent(),"write-",".tmp");Files.write(tmp,bytes);Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
      try(var files=Files.list(target.getParent())){List<Path> entries=files.filter(Files::isRegularFile).sorted(Comparator.comparingLong(ProviderStore::modified)).toList();for(int i=0;i<entries.size()-128;i++)Files.deleteIfExists(entries.get(i));}
    }catch(IOException e){throw new ProviderException("CACHE_WRITE_FAILED",500);}
  }
  private static long modified(Path path){try{return Files.getLastModifiedTime(path).toMillis();}catch(IOException e){return 0;}}
}
