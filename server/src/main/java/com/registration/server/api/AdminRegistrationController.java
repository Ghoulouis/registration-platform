package com.registration.server.api;

import com.registration.server.store.RegistrationStore;
import com.registration.server.store.RegistrationStore.RegisteredClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Read-only operational visibility into live CONFIRMED Registrations (ADR-0017), separate from
 * the Client-facing binary TCP protocol. Best-effort: see {@link RegistrationStore#listConfirmed}.
 */
@RestController
@Tag(name = "Admin Registrations")
public class AdminRegistrationController {

    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_LIMIT = 100;

    private final RegistrationStore store;

    public AdminRegistrationController(RegistrationStore store) {
        this.store = store;
    }

    @Operation(summary = "Count of currently live CONFIRMED Registrations (best-effort)")
    @GetMapping("/admin/registrations/count")
    public CountResponse count() {
        return new CountResponse(store.countConfirmed());
    }

    @Operation(summary = "Page of currently live CONFIRMED Registrations (best-effort, unsorted)")
    @GetMapping("/admin/registrations")
    public RegistrationsPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        List<RegisteredClient> items = store.listConfirmed(page, limit);
        return new RegistrationsPageResponse(items.stream().map(RegistrationItem::from).toList(), page, limit);
    }

    public record CountResponse(long count) {
    }

    public record RegistrationItem(String clientId, Instant expiresAt) {
        static RegistrationItem from(RegisteredClient client) {
            return new RegistrationItem(client.clientId().toString(), client.expiresAt());
        }
    }

    public record RegistrationsPageResponse(List<RegistrationItem> items, int page, int limit) {
    }
}
