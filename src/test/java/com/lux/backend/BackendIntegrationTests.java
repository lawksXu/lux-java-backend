package com.lux.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BackendIntegrationTests extends IntegrationSupport {
    @Autowired MockMvc mvc;

    @Test
    void exposesDefaultUserAndRequiresAnExplicitIdentity() throws Exception {
        mvc.perform(get("/api/v1/users/{user_id}", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(USER));
        mvc.perform(get("/api/v1/users/not-a-user")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users/invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createsAnOwnedSession() throws Exception {
        mvc.perform(post("/api/v1/users/{user_id}/sessions", USER)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"title\":\"Example\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id").value(USER))
                .andExpect(jsonPath("$.title").value("Example"));
    }
}
