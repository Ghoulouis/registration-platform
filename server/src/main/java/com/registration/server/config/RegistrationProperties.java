package com.registration.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Centralized Server-tuned Registration behavior (CONTEXT.md, ADR-0007). Default port
 * differs from the Distributed Server's (9000) so both can run side by side locally.
 */
@ConfigurationProperties(prefix = "registration")
public record RegistrationProperties(
        @DefaultValue("9001") int tcpPort,
        @DefaultValue("60") int validityPeriodSeconds,
        @DefaultValue("1000") long reaperIntervalMillis) {
}
