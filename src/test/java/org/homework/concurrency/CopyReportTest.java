package org.homework.concurrency;

import org.homework.concurrent.CopyReport;
import org.homework.concurrent.FileResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CopyReportTest {

    private static final Duration D = Duration.ofMillis(5);

    @Test
    void countsByStatusAndSumsOnlyCopiedBytes() {
        CopyReport report = new CopyReport(List.of(
                FileResult.copied("a", "a2", 100, D),
                FileResult.failed("b", "b2", D, "boom"),
                FileResult.copied("c", "c2", 50, D),
                FileResult.cancelled("d", "d2")), Duration.ofSeconds(1));

        assertEquals(2, report.copied());
        assertEquals(1, report.failed());
        assertEquals(1, report.cancelled());
        assertEquals(150, report.totalBytes());
        assertFalse(report.isSuccess());
    }

    @Test
    void emptyReportIsSuccessful() {
        assertTrue(new CopyReport(List.of(), D).isSuccess());
    }

    @Test
    void resultsAreImmutableAndKeepOrder() {
        List<FileResult> source = new ArrayList<>(List.of(FileResult.copied("a", "a2", 1, D), FileResult.copied("b", "b2", 2, D)));
        CopyReport report = new CopyReport(source, D);
        source.clear();

        assertEquals(List.of("a", "b"), report.results().stream().map(FileResult::source).toList());
        assertThrows(UnsupportedOperationException.class, () -> report.results().add(FileResult.cancelled("x", "y")));
    }

    @Test
    void failedResultNeedsErrorText() {
        assertThrows(IllegalArgumentException.class, () -> FileResult.failed("a", "b", D, null));
    }
}
