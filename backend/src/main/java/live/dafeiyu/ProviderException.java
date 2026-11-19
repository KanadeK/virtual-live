package live.dafeiyu;
/** Only public error codes go across API boundaries; remote bodies and credentials never do. */
public final class ProviderException extends RuntimeException {
  final String code;
  final int status;
  ProviderException(String code,int status){super(code);this.code=code;this.status=status;}
}
