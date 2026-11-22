package progress;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared, thread-safe scoreboard: download threads write progress, the renderer reads it.
 */
public class ProgressTracker {

    public static final String DOWNLOADING = "DOWNLOADING";
    public static final String RETRYING = "RETRYING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    private final ConcurrentHashMap<String, Long> nameToTotalSize = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> nameToBytesDownloaded = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> fileNameStatus = new ConcurrentHashMap<>();

    // Called when a download (or a retry of it) starts
    public void register(String fileName, long totalSize) {
        register(fileName, totalSize, 0);
    }

    // alreadyDownloaded > 0 when resuming from a .part file
    public void register(String fileName, long totalSize, long alreadyDownloaded) {
        nameToTotalSize.put(fileName, totalSize);
        nameToBytesDownloaded.put(fileName, new AtomicLong(alreadyDownloaded));
        fileNameStatus.put(fileName, DOWNLOADING);
    }

    // Called after every chunk is written
    public void addProgress(String fileName, long bytes) {
        AtomicLong counter = nameToBytesDownloaded.get(fileName);
        if (counter != null) {
            counter.addAndGet(bytes);
        }
    }

    public void markRetrying(String fileName) {
        fileNameStatus.put(fileName, RETRYING);
    }

    public void markDone(String fileName) {
        fileNameStatus.put(fileName, DONE);
    }

    public void markFailed(String fileName) {
        fileNameStatus.put(fileName, FAILED);
    }

    // Returns bytes downloaded so far for ONE file
    public long getDownloaded(String fileName) {
        AtomicLong counter = nameToBytesDownloaded.get(fileName);
        return counter == null ? 0 : counter.get();
    }

    // Returns total size for ONE file (-1 means unknown)
    public long getTotal(String fileName) {
        return nameToTotalSize.getOrDefault(fileName, -1L);
    }

    public String getStatus(String fileName) {
        return fileNameStatus.getOrDefault(fileName, "UNKNOWN");
    }

    // Returns all file names the tracker knows about
    public Set<String> getAllFiles() {
        return nameToTotalSize.keySet();
    }
}
