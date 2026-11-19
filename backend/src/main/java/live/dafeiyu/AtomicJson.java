package live.dafeiyu;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
final class AtomicJson {
  private AtomicJson() {}
  static void write(ObjectMapper json,Path file,Object value,String error){
    Path tmp=null;
    try{Files.createDirectories(file.getParent());tmp=Files.createTempFile(file.getParent(),"save-",".tmp");json.writeValue(tmp.toFile(),value);Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    catch(IOException failure){throw new ProviderException(error,500);}
    finally{if(tmp!=null)try{Files.deleteIfExists(tmp);}catch(IOException ignored){}}
  }
}
