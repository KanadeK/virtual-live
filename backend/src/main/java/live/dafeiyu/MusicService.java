package live.dafeiyu;

import org.springframework.stereotype.Service;
import java.util.*;

/** Room is the sole state monitor: music commits, task cancellation and owner rotation linearize together.
 * Never acquire a MusicService monitor; search/resolve/probe must run outside room.
 * Capture commits take capture -> room; LiveRoom never acquires the capture monitor. */
@Service
public final class MusicService {
  public static final int LIMIT=20;
  public record Entry(String entryId,MusicCatalog.Song song,long requestedAt,String source) {
    public Entry(String entryId,MusicCatalog.Song song,long requestedAt){this(entryId,song,requestedAt,song.source());}
  }
  record Selection(MusicCatalog.Song song,long generation) {}
  private final MusicCatalog catalog;private final LiveRoom room;
  private final List<Entry> playlist=new ArrayList<>(),backgrounds=new ArrayList<>();
  private List<MusicCatalog.Song> localSongs=List.of();
  private boolean paused,repeat=true,localStopped;private double volume=.45,position;private long revision,requestGeneration;private String notice="";
  private String playbackEntryId="",playbackPhase="WAITING_OWNER",playbackOwnerEpoch="";private long playbackReportedAt;
  public MusicService(MusicCatalog catalog,LiveRoom room){this.catalog=catalog;this.room=room;}
  NativeMusicBridge nativeBridge(){return room.nativeMusic();}
  String nativeTaskId(String eventId){return room.taskIdForEvent(eventId);}
  private Entry entry(MusicCatalog.Song song){return new Entry(UUID.randomUUID().toString(),song,System.currentTimeMillis());}
  private Entry current(){return !playlist.isEmpty()?playlist.getFirst():localStopped||backgrounds.isEmpty()?null:backgrounds.getFirst();}
  public Map<String,Object> request(String command){
    return enqueueRequest(searchRequest(command));
  }
  String semanticRequest(AiDecision decision,java.util.function.BooleanSupplier active){
    if(!active.getAsBoolean())return "这条点歌已过期，没有加入队列。";
    try{
      Selection song=searchDecision(decision);
      if(!active.getAsBoolean())return "这条点歌已过期，没有加入队列。";
      return semanticEnqueue(song,active);
    }catch(IllegalArgumentException failure){return "点歌没有成功："+InputFilter.clean(failure.getMessage(),100);}
  }
  String semanticEnqueue(Selection selected,java.util.function.BooleanSupplier active){synchronized(room){
    if(!active.getAsBoolean())return "这条点歌已过期，没有加入队列。";
    enqueueRequest(selected);MusicCatalog.Song song=selected.song();
    return "已加入《"+song.title()+"》，排在点歌列表第"+playlist.size()+"首；是否开播以播放器反馈为准。";
  }}
  // Keep network search outside both music and capture locks; the adapter checks its source before enqueueing.
  Selection searchDecision(AiDecision decision){
    if(!decision.requestsMusic()||decision.query().isBlank())throw new IllegalArgumentException("请补充想听的歌名");
    long generation;synchronized(room){capacity();generation=requestGeneration;}
    return new Selection(decision.artist().isBlank()?catalog.find(decision.query()):catalog.find(decision.query(),decision.artist()),generation);
  }
  Selection searchRequest(String command){
    if(command==null||!command.strip().matches("^点歌\\s+\\S[\\s\\S]*"))throw new IllegalArgumentException("请输入“点歌 歌名”，例如：点歌 稻香");
    String query=InputFilter.clean(command.strip().replaceFirst("^点歌\\s+",""),120);
    if(query.isBlank())throw new IllegalArgumentException("请输入歌曲名称");
    long generation;synchronized(room){capacity();generation=requestGeneration;}
    return new Selection(catalog.find(query),generation);
  }
  Map<String,Object> enqueueRequest(Selection selected){synchronized(room){
    if(selected.generation()!=requestGeneration)throw new IllegalArgumentException("点歌列表已清空，这条旧请求没有加入队列");
    MusicCatalog.Song song=selected.song();
    capacity();if(playlist.stream().anyMatch(e->e.song.songId().equals(song.songId())))throw new IllegalArgumentException("这首歌已在播放列表中");
    boolean wasEmpty=playlist.isEmpty();playlist.add(entry(song));if(wasEmpty){position=0;paused=false;renewBackground();}
    notice="已添加："+song.title();changed();return status();
  }}
  void requestNotice(String message){synchronized(room){notice=message;changed();}}
  /** Kept for callers explicitly adding a list; startup uses localBackground instead. */
  public Map<String,Object> addDefaults(List<MusicCatalog.Song> songs){synchronized(room){
    boolean wasEmpty=playlist.isEmpty();int added=0;Set<String> ids=new HashSet<>();playlist.forEach(e->ids.add(e.song.songId()));
    for(var song:songs){if(playlist.size()>=LIMIT)break;if(ids.add(song.songId())){playlist.add(entry(song));added++;}}
    if(wasEmpty&&added>0){position=0;paused=false;renewBackground();}repeat=true;notice="已添加 "+added+" 首歌曲";changed();return status();
  }}
  public Map<String,Object> localBackground(List<MusicCatalog.Song> songs){synchronized(room){
    localSongs=List.copyOf(songs);backgrounds.clear();songs.forEach(s->backgrounds.add(entry(s)));localStopped=false;
    if(playlist.isEmpty())position=0;
    notice=songs.isEmpty()?"未找到本地背景音乐":"本地背景音乐已就绪";changed();return status();
  }}
  private void capacity(){if(playlist.size()>=LIMIT)throw new IllegalArgumentException("播放列表已满（最多 20 首），请先移除歌曲");}
  public Map<String,Object> status(){synchronized(room){
    Entry c=current();Map<String,Object> s=new LinkedHashMap<>();s.put("playlist",List.copyOf(playlist));s.put("current",c==null?Map.of():c);
    s.put("paused",paused);s.put("repeat",repeat);s.put("volume",volume);s.put("position",position);s.put("limit",LIMIT);s.put("revision",revision);s.put("notice",notice);
    boolean same=c!=null&&c.entryId().equals(playbackEntryId)&&room.ownerEpoch().equals(playbackOwnerEpoch);
    s.put("playback",Map.of("phase",same?(paused?"PAUSED":playbackPhase):"WAITING_OWNER","reportedAt",same?playbackReportedAt:0,"confirmed",!room.nativeBlocksHtml()&&same&&!paused&&"PLAYING".equals(playbackPhase)&&System.currentTimeMillis()-playbackReportedAt<8000,"entryId",c==null?"":c.entryId(),"ownerEpoch",same?playbackOwnerEpoch:""));
    if(room.nativeMusic()!=null)s.put("native",room.nativeMusic().status());
    s.put("fallbackActive",playlist.isEmpty()&&c!=null);s.put("localPlaylist",List.copyOf(backgrounds));s.put("source",c==null?"none":c.source());return s;
  }}
  private void changed(){revision++;room.musicUpdate(status());}
  private void renewBackground(){if(!backgrounds.isEmpty()){Entry e=backgrounds.getFirst();backgrounds.set(0,entry(e.song));}}
  private void fallback(){localStopped=false;renewBackground();position=0;}
  public Map<String,Object> repeat(boolean value){synchronized(room){repeat=value;notice=value?"已开启列表循环":"已切换为顺序播放";changed();return status();}}
  public Map<String,Object> pause(boolean value){synchronized(room){paused=value;notice=value?"音乐已暂停":"音乐继续播放";changed();return status();}}
  public Map<String,Object> volume(double value){synchronized(room){if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("音量须为0～1");volume=value;changed();return status();}}
  public Map<String,Object> skip(){synchronized(room){
    if(current()==null&&localStopped&&!backgrounds.isEmpty()){localStopped=false;renewBackground();position=0;notice="继续本地背景音乐";changed();}
    else advance("已切到下一首",false,true);return status();
  }}
  public Map<String,Object> remove(String entryId){synchronized(room){
    int index=-1;for(int n=0;n<playlist.size();n++)if(playlist.get(n).entryId.equals(entryId)){index=n;break;}
    if(index<0)throw new IllegalArgumentException("歌曲已不在用户列表中");
    playlist.remove(index);if(index==0){position=0;if(playlist.isEmpty())fallback();}notice="歌曲已移除";changed();return status();
  }}
  public Map<String,Object> clear(){synchronized(room){requestGeneration++;playlist.clear();fallback();notice="用户列表已清空，回到本地背景音乐";changed();return status();}}
  private void advance(String message,boolean failed,boolean manual){
    if(!playlist.isEmpty()){
      Entry previous=playlist.removeFirst();if(repeat&&!failed)playlist.add(entry(previous.song));
      if(playlist.isEmpty())fallback();
    }else if(!backgrounds.isEmpty()&&!localStopped){
      Entry previous=backgrounds.removeFirst();
      if(failed){/* Do not retry a failed source until local defaults are explicitly refreshed. */}
      else if(repeat||manual)backgrounds.add(entry(previous.song));
      else if(backgrounds.isEmpty()){localSongs.forEach(s->backgrounds.add(entry(s)));localStopped=true;}
    }
    position=0;notice=message;changed();
  }
  public boolean completed(String entryId,String clientId,boolean failed){synchronized(room){
    return room.isOwner(clientId)&&completeCurrent(entryId,failed);
  }}
  private boolean completeCurrent(String entryId,boolean failed){
    Entry c=current();if(c==null||paused||!c.entryId.equals(entryId))return false;
    advance(failed?"“"+c.song.title()+"”无法播放，已跳过":"上一首播放完毕",failed,false);return true;
  }
  public Map<String,Object> prepare(String entryId,String clientId,String epoch){
    MusicCatalog.Song song;
    synchronized(room){Entry c=current();if(room.nativeBlocksHtml()||!room.isOwner(clientId,epoch)||c==null||!c.entryId().equals(entryId))throw new ProviderException("NOT_ACTIVE_MUSIC_OWNER",409);song=c.song();}
    MusicCatalog.Song ready=catalog.prepare(song);
    synchronized(room){Entry c=current();if(room.nativeBlocksHtml()||!room.isOwner(clientId,epoch)||c==null||!c.entryId().equals(entryId))throw new ProviderException("NOT_ACTIVE_MUSIC_OWNER",409);
      return Map.of("entryId",entryId,"ownerEpoch",epoch,"audioUrl",ready.audioUrl());}
  }
  public boolean completed(String entryId,String clientId,String epoch,boolean failed){synchronized(room){return !room.nativeBlocksHtml()&&room.isOwner(clientId,epoch)&&completeCurrent(entryId,failed);}}
  public boolean progress(String entryId,String clientId,String epoch,double seconds){synchronized(room){return !room.nativeBlocksHtml()&&room.isOwner(clientId,epoch)&&recordProgress(entryId,epoch,seconds);}}
  public boolean progress(String entryId,String clientId,double seconds){synchronized(room){
    return room.isOwner(clientId)&&recordProgress(entryId,room.ownerEpoch(clientId),seconds);
  }}
  private boolean recordProgress(String entryId,String epoch,double seconds){
    Entry c=current();if(c==null||!c.entryId.equals(entryId)||!Double.isFinite(seconds)||seconds<0||seconds>86400)return false;
    if(!paused&&seconds>position+.01){playbackEntryId=entryId;playbackPhase="PLAYING";playbackOwnerEpoch=epoch;playbackReportedAt=System.currentTimeMillis();}
    position=seconds;changed();return true;
  }
  public boolean playback(String entryId,String clientId,String epoch,String phase,double seconds){synchronized(room){
    Entry c=current();if(room.nativeBlocksHtml()||!room.isOwner(clientId,epoch)||c==null||!c.entryId().equals(entryId)||!Set.of("LOADING","PLAYING","PAUSED").contains(phase)||!Double.isFinite(seconds)||seconds<0||seconds>86400)return false;
    if(paused&&!"PAUSED".equals(phase))return false;
    playbackEntryId=entryId;playbackPhase=phase;playbackOwnerEpoch=epoch;playbackReportedAt=System.currentTimeMillis();position=seconds;changed();return true;
  }}
}
