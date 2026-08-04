package com.registration.client.simulation;

import com.registration.client.stats.OperationType;
import com.registration.client.stats.Stats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkReportTest {

    @Test
    void eachSnapshotAggregatesRegisterAndRenewOnlyAcrossTotals(@TempDir Path tempDir) throws IOException {
        Stats stats = new Stats();
        stats.forType(OperationType.REGISTER).recordAttempt(false);
        stats.forType(OperationType.REGISTER).recordOutcome(true);
        stats.forType(OperationType.REGISTER).recordResponseTime(10);
        stats.forType(OperationType.RENEW).recordAttempt(true);
        stats.forType(OperationType.RENEW).recordTimeout();
        stats.forType(OperationType.RENEW).recordOutcome(false);
        stats.forType(OperationType.CANCEL).recordAttempt(false); // must not leak into the report
        stats.forType(OperationType.CANCEL).recordOutcome(true);
        Path path = tempDir.resolve("benchmark-report.json");

        BenchmarkReport report = new BenchmarkReport();
        report.recordSnapshot(stats);
        report.write(path);

        String json = Files.readString(path);
        assertThat(json).contains("\"totalRequests\": 2"); // 1 REGISTER + 1 RENEW, CANCEL excluded
        assertThat(json).contains("\"totalTimeouts\": 1");
        assertThat(json).contains("\"totalRetries\": 1");
        assertThat(json).doesNotContain("cancel");
        assertThat(json).contains("\"timestamp\"");
    }

    @Test
    void responseTimesAreWindowedButCumulativeFiguresAreCarriedAlongside(@TempDir Path tempDir) throws IOException {
        Stats stats = new Stats();
        Path path = tempDir.resolve("benchmark-report.json");
        BenchmarkReport report = new BenchmarkReport();

        stats.forType(OperationType.REGISTER).recordResponseTime(10);
        stats.forType(OperationType.REGISTER).recordResponseTime(30);
        report.recordSnapshot(stats); // window/cumulative both see [10, 30] here

        stats.forType(OperationType.REGISTER).recordResponseTime(500);
        report.recordSnapshot(stats); // window now sees only [500]; cumulative still sees all three

        report.write(path);
        String json = Files.readString(path);

        assertThat(json).contains("\"maxResponseTimeMillis\": 30"); // first snapshot's window max
        assertThat(json).contains("\"maxResponseTimeMillis\": 500"); // second snapshot's window max
        // Both snapshots' cumulative max is the true whole-run max seen so far at that point.
        assertThat(json).contains("\"cumulativeMaxResponseTimeMillis\": 30");
        assertThat(json).contains("\"cumulativeMaxResponseTimeMillis\": 500");
    }

    @Test
    void writeProducesAJsonArrayWithOneEntryPerRecordedSnapshot(@TempDir Path tempDir) throws IOException {
        Stats stats = new Stats();
        Path path = tempDir.resolve("benchmark-report.json");

        BenchmarkReport report = new BenchmarkReport();
        report.recordSnapshot(stats);
        report.recordSnapshot(stats);
        report.recordSnapshot(stats);
        report.write(path);

        String content = Files.readString(path).trim();
        assertThat(content).startsWith("[");
        assertThat(content).endsWith("]");
        assertThat(occurrences(content, "\"timestamp\"")).isEqualTo(3);
    }

    private static long occurrences(String haystack, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        return matcher.results().count();
    }
}
