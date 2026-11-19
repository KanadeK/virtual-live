package live.dafeiyu;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigTest {
  @TempDir Path dir;
  final ObjectMapper json=new ObjectMapper();
  MockEnvironment env(){return new MockEnvironment().withProperty("integration.data-dir",dir.toString());}
  JsonNode patch(String text)throws Exception{return json.readTree(text);}
  @Test void settingsPersistWithRedactedReadsBlankPreserveAndExplicitClear()throws Exception{
    IntegrationConfig config=new IntegrationConfig(env());
    config.update(patch("{\"ai\":{\"provider\":\"deepseek\",\"apiKey\":\"fixture-ai-secret\",\"model\":\"deepseek-flash\"},\"tts\":{\"provider\":\"doubao\",\"apiKey\":\"fixture-tts-secret\",\"authMode\":\"apiKey\",\"speaker\":\"fixture-speaker\"}}"));
    assertTrue(config.deepseekReady());assertTrue(config.doubaoReady());
    assertFalse(json.writeValueAsString(config.status()).contains("fixture-ai-secret"));assertFalse(config.snapshot().toString().contains("fixture-tts-secret"));
    config.update(patch("{\"ai\":{\"apiKey\":\"\",\"dailyRequestLimit\":2},\"tts\":{\"apiKey\":null}}"));
    assertEquals("fixture-ai-secret",config.snapshot().deepseekKey());assertEquals("fixture-tts-secret",config.snapshot().doubaoApiKey());
    IntegrationConfig restored=new IntegrationConfig(env());assertTrue(restored.deepseekReady());assertEquals(2,restored.snapshot().aiDailyLimit());assertEquals("fixture-speaker",restored.snapshot().doubaoSpeaker());
    restored.update(patch("{\"clearSecrets\":[\"deepseekApiKey\",\"doubaoApiKey\"]}"));assertFalse(restored.deepseekReady());assertFalse(new IntegrationConfig(env()).doubaoReady());
  }
  @Test void invalidUpdatesCannotPartlyPublishOrPersist()throws Exception{
    IntegrationConfig config=new IntegrationConfig(env());var before=config.snapshot();
    assertThrows(IllegalArgumentException.class,()->config.update(patch("{\"ai\":{\"model\":\"changed\",\"maxOutputTokens\":200}}")));
    assertSame(before,config.snapshot());assertFalse(Files.exists(dir.resolve("runtime-config.json")));
    assertThrows(IllegalArgumentException.class,()->config.update(patch("{\"tts\":{\"secretKey\":\"not-supported\"}}")));
  }
  @Test void writeFailureDoesNotMakeNewConfigActive()throws Exception{
    IntegrationConfig config=new IntegrationConfig(env());Files.createDirectory(dir.resolve("runtime-config.json"));var before=config.snapshot();
    assertThrows(ProviderException.class,()->config.update(patch("{\"ai\":{\"model\":\"changed\"}}")));assertSame(before,config.snapshot());
  }
  @Test void authModeSelectsOneCredentialPairAndFingerprintsAreSeparated()throws Exception{
    IntegrationConfig config=new IntegrationConfig(env());var original=config.snapshot();
    config.update(patch("{\"tts\":{\"provider\":\"doubao\",\"authMode\":\"appToken\",\"appId\":\"app\",\"accessToken\":\"token\",\"apiKey\":\"other\"}}"));
    assertTrue(config.doubaoReady());assertNotEquals(original.ttsFingerprint(),config.snapshot().ttsFingerprint());
    config.update(patch("{\"tts\":{\"accessToken\":\"\"},\"clearSecrets\":[\"doubaoAccessToken\"]}"));assertFalse(config.doubaoReady());
    config.update(patch("{\"tts\":{\"authMode\":\"apiKey\"}}"));assertTrue(config.doubaoReady());
  }
  @Test void readersAlwaysSeeACompleteConfiguration()throws Exception{
    IntegrationConfig config=new IntegrationConfig(env());
    config.update(patch("{\"ai\":{\"model\":\"pair-a\",\"apiKey\":\"key-a\"}}"));
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
      var reader=executor.submit(()->{for(int i=0;i<100000;i++){var s=config.snapshot();if(s.deepseekModel().equals("pair-a"))assertEquals("key-a",s.deepseekKey());else {assertEquals("pair-b",s.deepseekModel());assertEquals("key-b",s.deepseekKey());}}});
      config.update(patch("{\"ai\":{\"model\":\"pair-b\",\"apiKey\":\"key-b\"}}"));reader.get();
    }
  }
}
