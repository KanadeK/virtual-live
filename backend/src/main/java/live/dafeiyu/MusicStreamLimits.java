package live.dafeiyu;

import java.io.*;
import java.util.concurrent.*;

final class MusicStreamLimits {
  static final long MAX_BYTES=64L*1024*1024;
  private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"music-stream-deadline");t.setDaemon(true);return t;});
  static ScheduledFuture<?> closeAfter(InputStream input,long millis){return TIMER.schedule(()->{try{input.close();}catch(IOException ignored){}},millis,TimeUnit.MILLISECONDS);}
  static void transfer(InputStream input,OutputStream output)throws IOException {
    var deadline=closeAfter(input,30000);
    try(input){byte[] buffer=new byte[8192];long total=0;for(int read;(read=input.read(buffer))!=-1;){total+=read;if(total>MAX_BYTES)throw new IOException("MUSIC_STREAM_TOO_LARGE");output.write(buffer,0,read);}}
    finally{deadline.cancel(false);}
  }
}

