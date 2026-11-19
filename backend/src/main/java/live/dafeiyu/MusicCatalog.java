package live.dafeiyu;

public interface MusicCatalog {
  record Song(String songId,String title,String artist,String songUrl,String audioUrl,long durationMs,String source) {
    public Song(String songId,String title,String artist,String songUrl,String audioUrl,long durationMs){this(songId,title,artist,songUrl,audioUrl,durationMs,"netease");}
  }
  Song find(String query);
  default Song find(String title,String artist){return find(title+(artist.isBlank()?"":" "+artist));}
  default Song prepare(Song song){return song;}
  default java.util.Map<String,Object> providerStatus(){return java.util.Map.of("provider","custom","configured",true,"controlAvailable",true,"notice","已配置音源");}
  default java.net.http.HttpResponse<java.io.InputStream> openStream(String token,String range)throws Exception{throw new ProviderException("MUSIC_STREAM_NOT_FOUND",404);}
}
