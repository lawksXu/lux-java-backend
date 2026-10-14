package com.lux.backend;

import com.lux.backend.common.ApiErrors;
import com.lux.backend.common.ApiException;
import com.lux.backend.user.UserController;
import com.lux.backend.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserApiTests {
    @Test
    void readsTheUserFromThePathAndRejectsMalformedOrUnknownUsers() throws Exception {
        String id = "00000000-0000-0000-0000-000000000001";
        UserService users = mock(UserService.class);
        when(users.get(id)).thenReturn(new UserService.UserRecord(id, "Local user", Instant.EPOCH));
        String unknown = "00000000-0000-0000-0000-000000000002";
        when(users.get(unknown)).thenThrow(ApiException.notFound());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserController(users))
                .setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/api/v1/users/{user_id}", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/v1/users/{user_id}", id).header("X-User-Id", unknown))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/v1/users/not-a-user"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users/{user_id}", unknown)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/users")).andExpect(status().isNotFound());
    }
}
