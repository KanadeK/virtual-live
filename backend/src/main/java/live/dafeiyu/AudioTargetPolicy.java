package live.dafeiyu;

import java.net.*;
import java.util.*;
import org.springframework.core.env.Environment;

/** Only named public audio hosts; the localhost test origin requires an explicit fixture flag. */
final class AudioTargetPolicy {
  private final Set<String> allowed;private final boolean fixture;private final int fixturePort;
  AudioTargetPolicy(Environment env,String gateway) {
    allowed=new HashSet<>();for(String host:env.getProperty("music.audio-hosts","").split(","))if(!host.isBlank())allowed.add(host.strip().toLowerCase(Locale.ROOT));
    fixture=env.getProperty("music.allow-loopback-fixture",Boolean.class,false);
    int port=-1;try{port=URI.create(gateway).getPort();}catch(Exception ignored){}fixturePort=port;
  }
  void validate(URI uri) {
    try {
      String host=uri.getHost();if(host==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException();
      if(fixture&&"http".equals(uri.getScheme())&&"127.0.0.1".equals(host)&&uri.getPort()==fixturePort&&fixturePort>0)return;
      if(!"https".equals(uri.getScheme())||uri.getPort()!=-1&&uri.getPort()!=443||!allowed.contains(host.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();
      for(InetAddress ip:InetAddress.getAllByName(host)){
        byte[] bytes=ip.getAddress();int first=bytes[0]&255,second=bytes.length>1?bytes[1]&255:0;
        if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress()
          ||bytes.length==4&&(first==0||first>=224||first==100&&second>=64&&second<=127||first==192&&second==0||first==198&&(second==18||second==19))
          ||bytes.length==16&&((first&254)==252))throw new IllegalArgumentException();
      }
    }catch(Exception rejected){throw new IllegalArgumentException("音源地址不在已核验的公开音频目标范围");}
  }
}

