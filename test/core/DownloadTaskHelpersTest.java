package core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadTaskHelpersTest {

    @ParameterizedTest
    @CsvSource({
            "https://site.com/files/report.pdf,        report.pdf",
            "https://site.com/files/report.pdf?v=2,    report.pdf",
            "https://site.com/image.jpg?a=1&b=2,       image.jpg",
            "https://httpbin.org/image/png,            png",
            "https://site.com/folder/,                 download",
    })
    void extractsFileNameFromUrl(String url, String expected) {
        assertEquals(expected, DownloadTask.extractFileName(url));
    }

    @Test
    void parsesRetryAfterSeconds() {
        assertEquals(120_000, DownloadTask.parseRetryAfter("120"));
        assertEquals(3_000, DownloadTask.parseRetryAfter(" 3 "));
    }

    @Test
    void ignoresMissingOrInvalidRetryAfter() {
        assertEquals(0, DownloadTask.parseRetryAfter(null));
        assertEquals(0, DownloadTask.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(0, DownloadTask.parseRetryAfter("-5"));
    }

    @Test
    void parsesTotalSizeFromContentRange() {
        assertEquals(12345, DownloadTask.parseTotalFromContentRange("bytes */12345"));
        assertEquals(1000, DownloadTask.parseTotalFromContentRange("bytes 0-499/1000"));
        assertEquals(-1, DownloadTask.parseTotalFromContentRange(null));
        assertEquals(-1, DownloadTask.parseTotalFromContentRange("bytes */*"));
    }
}
