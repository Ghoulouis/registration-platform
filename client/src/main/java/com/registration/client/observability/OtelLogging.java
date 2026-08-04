package com.registration.client.observability;

import com.registration.common.observability.RegistrationEventLog;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Wires the real OpenTelemetry Logs SDK (ADR-0018) so every Logback event — framework logs
 * and {@link RegistrationEventLog} alike — ships as OTLP to the Collector, alongside the
 * existing plain-text console appender (kept as-is; see logback-spring.xml). The appender
 * itself is configured statically in logback-spring.xml and buffers/replays a bounded number
 * of events until {@link OpenTelemetryAppender#install} connects it to a real instance here.
 */
@Component
public class OtelLogging {

    private static final Logger log = LoggerFactory.getLogger(OtelLogging.class);

    private final OpenTelemetry openTelemetry;

    public OtelLogging(@Value("${otel.exporter.otlp.endpoint:http://localhost:4317}") String otlpEndpoint) {
        OpenTelemetry otelInstance;
        try {
            Resource resource = Resource.getDefault()
                    .merge(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "server")));

            // Thêm Timeout 3s để không làm nghẽn luồng khởi động nếu Collector không phản hồi
            OtlpGrpcLogRecordExporter logExporter = OtlpGrpcLogRecordExporter.builder()
                    .setEndpoint(otlpEndpoint)
                    .setTimeout(Duration.ofSeconds(3))
                    .build();

            SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
                    .setResource(resource)
                    .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
                    .build();

            otelInstance = OpenTelemetrySdk.builder()
                    .setLoggerProvider(loggerProvider)
                    .build();

            OpenTelemetryAppender.install(otelInstance);
            log.info("Successfully initialized OpenTelemetry Logging exporter to {}", otlpEndpoint);

        } catch (Exception e) {
            // CATCH TOÀN BỘ LỖI: Giúp app sống dai dù OTel Collector sập
            log.error("Failed to initialize OpenTelemetry Logging (endpoint: {}). Falling back to Noop instance.", otlpEndpoint, e);
            otelInstance = OpenTelemetry.noop();
        }

        this.openTelemetry = otelInstance;
    }

    @PreDestroy
    public void shutdown() {
        if (openTelemetry instanceof OpenTelemetrySdk sdk) {
            sdk.getSdkLoggerProvider().shutdown();
        }
    }
}