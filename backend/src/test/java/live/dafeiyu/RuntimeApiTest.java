package live.dafeiyu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"integration.ai-provider=mock","integration.tts-provider=browser"}) @AutoConfigureMockMvc
class RuntimeApiTest {
  static final Path root;
  static {try{root=Files.createTempDirectory("ds-runtime-api-");Files.createDirectories(root.resolve("music"));Files.write(root.resolve("music/background.wav"),DoubaoTtsService.wav(new byte[960]));}catch(Exception e){throw new ExceptionInInitializerError(e);}}
  @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("integration.data-dir",()->root.resolve("providers").toString());p.add("music.local-dir",()->root.resolve("music").toString());}
  @Autowired MockMvc mvc;@Autowired ObjectMapper json;
  @Test void corpusCrudAndIdleSettingsHaveUsableRestRoutes()throws Exception{
    String state=mvc.perform(post("/api/corpus").contentType("application/json").content("{\"type\":\"gift\",\"text\":\"测试感谢{nickname}\",\"enabled\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var items=json.readTree(state).path("items");String id=items.get(items.size()-1).path("id").asText();
    mvc.perform(put("/api/corpus/"+id).contentType("application/json").content("{\"text\":\"更新感谢{nickname}\",\"enabled\":false}")).andExpect(status().isOk());
    mvc.perform(get("/api/corpus?type=gift")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("gift"));
    mvc.perform(put("/api/corpus/idle").contentType("application/json").content("{\"enabled\":false,\"intervalSeconds\":120,\"requireRecentActivitySeconds\":300}")).andExpect(status().isOk()).andExpect(jsonPath("$.idle.enabled").value(false));
    mvc.perform(delete("/api/corpus/"+id)).andExpect(status().isOk());
    mvc.perform(post("/api/corpus").contentType("application/json").content("{\"type\":\"idle\",\"text\":\"错误{nickname}\"}")).andExpect(status().isBadRequest());
  }
  @Test void configurationNeverReturnsKeysAndUsageReturnsRawTokens()throws Exception{
    String body=mvc.perform(put("/api/integrations/config").contentType("application/json").content("{\"ai\":{\"apiKey\":\"fixture-private-value\",\"dailyRequestLimit\":0}}")).andExpect(status().isOk()).andExpect(jsonPath("$.ai.apiKeyConfigured").value(true)).andReturn().getResponse().getContentAsString();assertFalse(body.contains("fixture-private-value"));
    mvc.perform(get("/api/integrations/config")).andExpect(status().isOk()).andExpect(jsonPath("$.ai.apiKey").doesNotExist());
    mvc.perform(put("/api/integrations/config").contentType("application/json").content("{\"clearSecrets\":[\"deepseekApiKey\"]}")).andExpect(status().isOk()).andExpect(jsonPath("$.ai.apiKeyConfigured").value(false));
    mvc.perform(get("/api/integrations/status")).andExpect(status().isOk()).andExpect(jsonPath("$.usage.aiTotalTokens").value(0));
    mvc.perform(put("/api/integrations/config").contentType("application/json").content("{\"ai\":{\"maxOutputTokens\":1000}}")).andExpect(status().isBadRequest());
  }
  @Test void localMediaIsSameOriginSupportsRangesAndRejectsUnknownIds()throws Exception{
    String state=mvc.perform(get("/api/music/status")).andExpect(status().isOk()).andExpect(jsonPath("$.source").value("local")).andExpect(jsonPath("$.playlist.length()").value(0)).andReturn().getResponse().getContentAsString();
    String url=json.readTree(state).path("current").path("song").path("audioUrl").asText();
    mvc.perform(get(url).header("Range","bytes=0-3")).andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 0-3/1004")).andExpect(content().bytes(new byte[]{82,73,70,70}));
    mvc.perform(get("/api/music/local/"+"a".repeat(64))).andExpect(status().isNotFound());
  }
}
