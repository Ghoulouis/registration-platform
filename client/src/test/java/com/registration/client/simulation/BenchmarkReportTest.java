package com.registration.client.simulation;

import com.registration.client.stats.OperationType;
import com.registration.client.stats.Stats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkReportTest {

    @Test
    void jsonAggregatesRegisterAndRenewOnlyAcrossTotals() {
        Stats stats = new Stats();
        stats.forType(OperationType.REGISTER).recordAttempt(false);
        stats.forType(OperationType.REGISTER).recordOutcome(true);
        stats.forType(OperationType.REGISTER).recordResponseTime(10);
        stats.forType(OperationType.RENEW).recordAttempt(true);
        stats.forType(OperationType.RENEW).recordTimeout();
        stats.forType(OperationType.RENEW).recordOutcome(false);
        stats.forType(OperationType.CANCEL).recordAttempt(false); // must not leak into the report
        stats.forType(OperationType.CANCEL).recordOutcome(true);

        String json = BenchmarkReport.toJson(stats);

        assertThat(json).contains("\"totalRequests\": 2"); // 1 REGISTER + 1 RENEW, CANCEL excluded
        assertThat(json).contains("\"totalTimeouts\": 1");
        assertThat(json).contains("\"totalRetries\": 1");
        assertThat(json).doesNotContain("cancel");
    }

    @Test
    void writePersistsValidJsonToDisk(@TempDir Path tempDir) throws IOException {
        Stats stats = new Stats();
        stats.forType(OperationType.REGISTER).recordAttempt(false);
        stats.forType(OperationType.REGISTER).recordOutcome(true);
        Path path = tempDir.resolve("benchmark-report.json");

        BenchmarkReport.write(stats, path);

        String content = Files.readString(path);
        assertThat(content).contains("\"successes\": 1");
        assertThat(content).startsWith("{");
        assertThat(content.trim()).endsWith("}");
    }
}
