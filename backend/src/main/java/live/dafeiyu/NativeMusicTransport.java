package live.dafeiyu;

/** Private backend transport. Neither URL nor token is supplied by a browser/model. */
interface NativeMusicTransport {
  record Request(String requestId,String eventId,long observedAt,String source,String ownerEpoch,String sourceEpoch,String query,String artist,String uid,String name) {}
  record Prepared(String requestId,String reservation,String trackId,String title,String artist) {}
  record Track(String requestId,String id,String title,String artist){}
  record Observation(boolean pauseConfirmed,boolean playbackConfirmed,boolean completeSongVerified,double position,String trackId,boolean paused,String title,String artist,double duration,java.util.List<Track> queue) {
    Observation(boolean pauseConfirmed,boolean playbackConfirmed,boolean completeSongVerified,double position,String trackId,boolean paused){this(pauseConfirmed,playbackConfirmed,completeSongVerified,position,trackId,paused,"","",0,java.util.List.of());}
  }
  void context(long revision,String owner,String source,long expiresAt,boolean active);
  Prepared prepare(Request request);
  void commit(Prepared prepared);
  default void commit(Prepared prepared,long deadlineNanos){commit(prepared);}
  void cancel(String requestId);
  Observation observe();
  void pause(long revision,String owner,String source,boolean paused);
  void speech(long revision,String owner,String source,String taskId,boolean active,long expiresAt);
  long COMMIT_BUDGET_NANOS=6_000_000_000L;
}
