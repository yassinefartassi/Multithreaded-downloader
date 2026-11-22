package progress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgressRendererTest {

    @Test
    void formatsBytesInReadableUnits() {
        assertEquals("512 B", ProgressRenderer.formatBytes(512));
        assertEquals("1.0 KB", ProgressRenderer.formatBytes(1024));
        assertEquals("5.2 MB", ProgressRenderer.formatBytes(5_452_595));
        assertEquals("2.00 GB", ProgressRenderer.formatBytes(2L * 1024 * 1024 * 1024));
    }

    @Test
    void barIsEmptyHalfAndFull() {
        int w = ProgressRenderer.BAR_WIDTH;
        assertEquals("[" + "-".repeat(w) + "]", ProgressRenderer.buildBar(0));
        assertEquals("[" + "#".repeat(w / 2) + "-".repeat(w - w / 2) + "]", ProgressRenderer.buildBar(50));
        assertEquals("[" + "#".repeat(w) + "]", ProgressRenderer.buildBar(100));
    }

    @Test
    void barClampsOutOfRangeValues() {
        assertEquals(ProgressRenderer.buildBar(0), ProgressRenderer.buildBar(-20));
        assertEquals(ProgressRenderer.buildBar(100), ProgressRenderer.buildBar(150));
    }

    @Test
    void lineShowsPercentageAndStatus() {
        String line = ProgressRenderer.formatLine("file.zip", 500, 1000, ProgressTracker.DONE, 0);
        assertTrue(line.contains(" 50%"), line);
        assertTrue(line.contains("✓ done"), line);
    }

    @Test
    void lineHandlesUnknownSize() {
        String line = ProgressRenderer.formatLine("stream.bin", 2048, -1, ProgressTracker.DOWNLOADING, 1024);
        assertTrue(line.contains("size unknown"), line);
        assertTrue(line.contains("2.0 KB"), line);
    }

    @Test
    void longNamesAreShortened() {
        String line = ProgressRenderer.formatLine("a-really-long-file-name-here.tar.gz", 1, 10, ProgressTracker.DOWNLOADING, 0);
        assertTrue(line.startsWith("a-really-long-fil..."), line);
    }
}
