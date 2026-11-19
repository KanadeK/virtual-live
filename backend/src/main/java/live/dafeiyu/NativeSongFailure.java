package live.dafeiyu;

/** Only fixed public rejection categories cross the private bridge; remote text is never surfaced. */
final class NativeSongFailure {
  private NativeSongFailure(){}
  static ProviderException rejected(String code){
    return new ProviderException(switch(code){
      case "ambiguous_song"->"QQ_SONG_AMBIGUOUS";
      case "song_metadata_mismatch"->"QQ_SONG_METADATA_MISMATCH";
      case "qq_metadata_unavailable"->"QQ_SONG_METADATA_UNAVAILABLE";
      case "qq_metadata_unverified"->"QQ_SONG_METADATA_UNVERIFIED";
      case "qq_reconnect_not_ready"->"QQ_RECONNECT_NOT_READY";
      default->"QQ_NORMAL_REQUEST_REJECTED";
    },409);
  }
  static boolean definitive(ProviderException error){
    return switch(error.code){
      case "QQ_SONG_AMBIGUOUS","QQ_SONG_METADATA_MISMATCH","QQ_SONG_METADATA_UNAVAILABLE","QQ_SONG_METADATA_UNVERIFIED","QQ_RECONNECT_NOT_READY","QQ_NORMAL_REQUEST_REJECTED"->true;
      default->false;
    };
  }
  static String message(ProviderException error,String fallback){
    return switch(error.code){
      case "QQ_RECONNECT_NOT_READY"->"QQ重连后的播放状态未能确认，本次点歌未完成；网页音乐保持停止，等待真实暂停和清理确认。";
      case "QQ_SONG_AMBIGUOUS"->"找到多个同名QQ曲目或版本，请补充歌手及完整版本名；完整名称仍相同时，请主播在QQ音乐手动选择目标版本，这次没有加入队列。";
      case "QQ_SONG_METADATA_MISMATCH"->"没有找到歌名、歌手和版本完全一致的QQ曲目，请核对完整歌名后重试，这次没有加入队列。";
      case "QQ_SONG_METADATA_UNAVAILABLE"->"暂时无法读取QQ曲目版本信息，请稍后重试，这次没有加入队列。";
      case "QQ_SONG_METADATA_UNVERIFIED"->"QQ曲目版本信息未能一致核对，请稍后重试或在QQ音乐确认版本，这次没有加入队列。";
      default->fallback;
    };
  }
}
