package com.registration.server.api;

import com.registration.common.protocol.ClientId;
import com.registration.server.store.RegistrationStore;
import com.registration.server.store.RegistrationStore.RegisteredClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminRegistrationController.class)
class AdminRegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RegistrationStore store;

    @Test
    void countReturnsTheStoresCount() throws Exception {
        when(store.countConfirmed()).thenReturn(42L);

        mockMvc.perform(get("/admin/registrations/count"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"count\":42}"));
    }

    @Test
    void listReturnsAPageOfEntries() throws Exception {
        ClientId clientId = ClientId.parse("123456789012");
        Instant expiresAt = Instant.parse("2026-01-01T00:00:00Z");
        when(store.listConfirmed(anyInt(), anyInt()))
                .thenReturn(List.of(new RegisteredClient(clientId, expiresAt)));

        mockMvc.perform(get("/admin/registrations").param("page", "0").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "items": [{"clientId": "123456789012", "expiresAt": "2026-01-01T00:00:00Z"}],
                          "page": 0,
                          "limit": 10
                        }
                        """));
    }

    @Test
    void rejectsNegativePage() throws Exception {
        mockMvc.perform(get("/admin/registrations").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsLimitBelowOne() throws Exception {
        mockMvc.perform(get("/admin/registrations").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsLimitAboveMax() throws Exception {
        mockMvc.perform(get("/admin/registrations").param("limit", "1001"))
                .andExpect(status().isBadRequest());
    }
}
