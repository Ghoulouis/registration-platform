package com.registration.client.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wires the real OpenTelemetry Logs SDK (ADR-0018) so every Logback event — framework logs
 * and RegistrationEventLog alike — ships as OTLP to the Collector, alongside the existing
 * plain-text console appender (kept as-is; see logback-spring.xml). The appender itself is
 * configured statically in logback-spring.xml and buffers/replays a bounded number of events
 * until {@link OpenTelemetryAppender#install} connects it to a real instance here.
 */
@Component
public class OtelLogging {

    private final OpenTelemetrySdk openTelemetry;

    public OtelLogging(@Value("${otel.exporter.otlp.endpoint:http://localhost:4317}") String otlpEndpoint) {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "client")));

        SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(
                                OtlpGrpcLogRecordExporter.builder().setEndpoint(otlpEndpoint).build())
                        .build())
                .build();

        this.openTelemetry = OpenTelemetrySdk.builder()
                .setLoggerProvider(loggerProvider)
                .build();

        OpenTelemetryAppender.install(openTelemetry);
    }

    /**
     * Flushes and closes the export pipeline. Called explicitly from
     * {@code ClientSimulatorRunner}'s own {@code shutdown()} (ADR-0018), in addition to
     * {@link #onContextClose}: Spring's own context-close shutdown hook races the Simulated
     * Clients' independent manual shutdown hook thread with no ordering guarantee between the
     * two, so relying on {@code @PreDestroy} alone risks the final CANCEL's buffered logs
     * being dropped if the SDK shuts down first. Safe to call more than once.
     */
    public void shutdown() {
        openTelemetry.getSdkLoggerProvider().shutdown();
    }

    @PreDestroy
    public void onContextClose() {
        shutdown();
    }
}
