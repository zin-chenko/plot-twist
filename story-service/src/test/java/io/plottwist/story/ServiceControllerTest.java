package io.plottwist.story;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ServiceController.class)
class ServiceControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void identifiesService() throws Exception {
        mvc.perform(get("/api/story"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("story-service"))
            .andExpect(jsonPath("$.status").value("running"));
    }
}
