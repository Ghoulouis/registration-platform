package com.registration.server.observability;

import com.registration.common.observability.RegistrationEventLog;
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
 * and {@link RegistrationEventLog} alike — ships as OTLP to the Collector, alongside the
 * existing plain-text console appender (kept as-is; see logback-spring.xml). The appender
 * itself is configured statically in logback-spring.xml and buffers/replays a bounded number
 * of events until {@link OpenTelemetryAppender#install} connects it to a real instance here.
 */
@Component
public class OtelLogging {

    private final OpenTelemetrySdk openTelemetry;

    public OtelLogging(@Value("${otel.exporter.otlp.endpoint:http://localhost:4317}") String otlpEndpoint) {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "server")));

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

    @PreDestroy
    public void shutdown() {
        openTelemetry.getSdkLoggerProvider().shutdown();
    }
}
