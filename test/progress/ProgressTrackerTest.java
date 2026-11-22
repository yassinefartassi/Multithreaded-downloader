package progress;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProgressTrackerTest {

    @Test
    void registerStartsAtZeroAndDownloading() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.register("a.pdf", 1000);

        assertEquals(0, tracker.getDownloaded("a.pdf"));
        assertEquals(1000, tracker.getTotal("a.pdf"));
        assertEquals(ProgressTracker.DOWNLOADING, tracker.getStatus("a.pdf"));
        assertTrue(tracker.getAllFiles().contains("a.pdf"));
    }

    @Test
    void registerWithResumeOffsetStartsFromThatOffset() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.register("big.iso", 1000, 400);
        tracker.addProgress("big.iso", 100);

        assertEquals(500, tracker.getDownloaded("big.iso"));
    }

    @Test
    void statusTransitions() {
        ProgressTracker tracker = new ProgressTracker();
        tracker.register("a.pdf", 10);

        tracker.markRetrying("a.pdf");
        assertEquals(ProgressTracker.RETRYING, tracker.getStatus("a.pdf"));
        tracker.markDone("a.pdf");
        assertEquals(ProgressTracker.DONE, tracker.getStatus("a.pdf"));
        tracker.markFailed("a.pdf");
        assertEquals(ProgressTracker.FAILED, tracker.getStatus("a.pdf"));
    }

    @Test
    void unknownFilesHaveSafeDefaults() {
        ProgressTracker tracker = new ProgressTracker();

        assertEquals(0, tracker.getDownloaded("nope"));
        assertEquals(-1, tracker.getTotal("nope"));
        assertEquals("UNKNOWN", tracker.getStatus("nope"));
        assertDoesNotThrow(() -> tracker.addProgress("nope", 5));
    }

    @Test
    void concurrentUpdatesAreNotLost() throws InterruptedException {
        ProgressTracker tracker = new ProgressTracker();
        tracker.register("shared.bin", -1);

        int threads = 8;
        int updatesPerThread = 10_000;
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < updatesPerThread; j++) {
                    tracker.addProgress("shared.bin", 1);
                }
            });
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) {
            t.join();
        }

        // With a plain long and +=, this would usually come out lower
        assertEquals((long) threads * updatesPerThread, tracker.getDownloaded("shared.bin"));
    }
}
