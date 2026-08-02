package com.registration.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-tuned Registration behavior (see CONTEXT.md — the Validity Period is
 * managed by the Server, not the Client).
 */
@ConfigurationProperties(prefix = "registration")
public record RegistrationProperties(int tcpPort, int validityPeriodSeconds, int workerPoolSize) {
}
