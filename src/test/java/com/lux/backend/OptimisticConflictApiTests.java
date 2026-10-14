package com.lux.backend;

import com.lux.backend.common.ApiErrors;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OptimisticConflictApiTests {
    @Test
    void concurrentUpdatesHaveAnActionableConflictResponse() {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConflictingController())
                .setControllerAdvice(new ApiErrors()).build();
        assertDoesNotThrow(() -> mvc.perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION")));
    }

    @RestController
    static class ConflictingController {
        @GetMapping("/conflict")
        String update() {
            throw new OptimisticLockingFailureException("Stale version");
        }
    }
}
