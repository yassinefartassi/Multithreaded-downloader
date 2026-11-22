package core;

import model.DownloadResult;
import progress.ProgressRenderer;
import progress.ProgressTracker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs many DownloadTasks in parallel on a fixed thread pool and collects their results.
 */
public class DownloadManager {

    private final int threadCount;
    private final Path outputDir;
    private final RetryPolicy retryPolicy;
    private final boolean showProgress;
    private final ProgressTracker tracker = new ProgressTracker(); // shared by all tasks

    public DownloadManager(int threadCount, Path outputDir) {
        this(threadCount, outputDir, RetryPolicy.defaults(), true);
    }

    public DownloadManager(int threadCount, Path outputDir, RetryPolicy retryPolicy, boolean showProgress) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("threadCount must be at least 1");
        }
        this.threadCount = threadCount;
        this.outputDir = outputDir;
        this.retryPolicy = retryPolicy;
        this.showProgress = showProgress;
    }

    public ProgressTracker getTracker() {
        return tracker;
    }

    public List<DownloadResult> downloadAll(List<String> urls) {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<DownloadResult>> futures = new ArrayList<>();
        List<DownloadResult> results = new ArrayList<>();

        // Start the renderer on its own thread
        ProgressRenderer renderer = new ProgressRenderer(tracker);
        Thread rendererThread = new Thread(renderer, "progress-renderer");
        rendererThread.setDaemon(true); // won't keep the program alive by itself
        if (showProgress) {
            rendererThread.start();
        }

        try {
            // 1. Submit every task: they start running immediately, in parallel
            for (String url : urls) {
                futures.add(pool.submit(new DownloadTask(url, outputDir, tracker, retryPolicy)));
            }

            // 2. Wait for each one and collect its result
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pool.shutdownNow(); // cancel remaining downloads
                    break;
                } catch (ExecutionException e) {
                    // Only happens if call() throws unexpectedly
                    results.add(new DownloadResult("unknown", urls.get(i), false, 0, 0,
                            String.valueOf(e.getCause()), 1));
                }
            }
        } finally {
            // 3. Always shut down, otherwise the program never exits
            pool.shutdown();

            // Stop the renderer and wait for its final draw
            if (showProgress) {
                renderer.stop();
                try {
                    rendererThread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return results;
    }
}
