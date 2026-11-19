package live.dafeiyu;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc
class ApiTest {
 @Autowired MockMvc mvc;
 @Test void directionalWalkingActionsAreAccepted()throws Exception{for(String action:new String[]{"walk_left","walk_right"})mvc.perform(post("/api/character/action").contentType("application/json").content("{\"action\":\""+action+"\"}")).andExpect(status().isOk());}
 @Test void healthAndValidation()throws Exception{mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.java").value(Runtime.version().feature()));mvc.perform(post("/api/demo/event").contentType("application/json").content("{\"type\":\"GIFT\",\"giftCount\":-1}")).andExpect(status().isBadRequest());mvc.perform(post("/api/character/action").contentType("application/json").content("{\"action\":\"unknown\"}")).andExpect(status().isBadRequest());mvc.perform(post("/api/demo/auto/start").contentType("application/json").content("{\"minSeconds\":15,\"maxSeconds\":5}")).andExpect(status().isBadRequest());}
}
