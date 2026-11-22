package core;

import model.DownloadResult;
import progress.ProgressTracker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;

/**
 * Downloads ONE file. Features:
 *  - writes to "name.part" first, renames when complete
 *  - resumes an existing .part file with an HTTP Range request
 *  - retries temporary failures (429, 5xx, network errors) with exponential backoff
 */
public class DownloadTask implements Callable<DownloadResult> {

    private static final int BUFFER_SIZE = 8192;
    private static final int TIMEOUT_MS = 10_000;
    private static final String USER_AGENT = "MultithreadedFileDownloader/1.0";

    private final String url;
    private final Path outputDir;
    private final ProgressTracker tracker;
    private final RetryPolicy retryPolicy;

    public DownloadTask(String url, Path outputDir, ProgressTracker tracker) {
        this(url, outputDir, tracker, RetryPolicy.defaults());
    }

    public DownloadTask(String url, Path outputDir, ProgressTracker tracker, RetryPolicy retryPolicy) {
        this.url = url;
        this.outputDir = outputDir;
        this.tracker = tracker;
        this.retryPolicy = retryPolicy;
    }

    @Override
    public DownloadResult call() {
        long startTime = System.currentTimeMillis();
        String fileName = extractFileName(url);
        Path partFile = outputDir.resolve(fileName + ".part");
        Path finalFile = outputDir.resolve(fileName);

        Exception lastError = null;
        int attempt = 0;

        while (attempt < retryPolicy.getMaxAttempts()) {
            attempt++;
            try {
                Files.createDirectories(outputDir);

                long fileSize = downloadOnce(fileName, partFile);

                // Success: rename .part -> real name
                Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                tracker.markDone(fileName);
                long elapsed = System.currentTimeMillis() - startTime;
                return new DownloadResult(fileName, url, true, fileSize, elapsed, null, attempt);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // cancelled: stop, don't retry
                lastError = e;
                break;
            } catch (Exception e) {
                lastError = e;
                if (attempt >= retryPolicy.getMaxAttempts() || !isRetryable(e)) {
                    break;
                }
                long serverHint = (e instanceof HttpStatusException h) ? h.getRetryAfterMs() : 0;
                long delay = retryPolicy.delayAfterAttempt(attempt, serverHint);

                tracker.markRetrying(fileName);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Failure. The .part file is kept on purpose: the next run resumes from it.
        tracker.markFailed(fileName);
        long elapsed = System.currentTimeMillis() - startTime;
        String message = lastError == null ? "unknown error" : lastError.getMessage();
        return new DownloadResult(fileName, url, false, 0, elapsed, message, attempt);
    }

    /**
     * One attempt. Resumes from the .part file if it exists.
     * Returns the final size of the .part file.
     */
    private long downloadOnce(String fileName, Path partFile) throws IOException, InterruptedException {
        long existing = Files.exists(partFile) ? Files.size(partFile) : 0;

        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (existing > 0) {
                // "Send me everything from byte N onwards"
                connection.setRequestProperty("Range", "bytes=" + existing + "-");
            }

            int status = connection.getResponseCode();
            boolean append;
            long totalSize;

            if (status == HttpURLConnection.HTTP_PARTIAL) {
                // 206: server honoured the Range -> append to what we have
                append = true;
                long remaining = connection.getContentLengthLong();
                totalSize = remaining >= 0 ? existing + remaining : -1;
            } else if (status == HttpURLConnection.HTTP_OK) {
                // 200: full file (no Range sent, or the server ignores Range) -> start over
                append = false;
                existing = 0;
                totalSize = connection.getContentLengthLong();
            } else if (status == 416 && existing > 0) {
                // 416 Range Not Satisfiable: our .part is already complete, or doesn't match the file
                long serverSize = parseTotalFromContentRange(connection.getHeaderField("Content-Range"));
                if (serverSize == existing) {
                    tracker.register(fileName, existing, existing);
                    return existing;
                }
                Files.write(partFile, new byte[0]); // truncate, the next attempt starts from 0
                throw new IOException("Partial file did not match server file, restarting");
            } else {
                throw new HttpStatusException(status, parseRetryAfter(connection.getHeaderField("Retry-After")));
            }

            tracker.register(fileName, totalSize, existing);

            OutputStream out = append
                    ? Files.newOutputStream(partFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    : Files.newOutputStream(partFile, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            long written = existing;
            try (InputStream in = connection.getInputStream(); out) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Download cancelled");
                    }
                    out.write(buffer, 0, bytesRead);
                    written += bytesRead;
                    tracker.addProgress(fileName, bytesRead);
                }
            }

            if (totalSize > 0 && written != totalSize) {
                // Connection dropped early. IOException -> retryable, and the retry resumes.
                throw new IOException("Incomplete download: " + written + " of " + totalSize + " bytes");
            }
            return written;

        } finally {
            connection.disconnect();
        }
    }

    private boolean isRetryable(Exception e) {
        if (e instanceof HttpStatusException h) {
            return retryPolicy.isRetryableStatus(h.getStatus());
        }
        // Timeouts, connection resets, incomplete downloads... are usually temporary
        return e instanceof IOException;
    }

    // "https://site.com/files/report.pdf?v=2" -> "report.pdf"
    static String extractFileName(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int queryStart = name.indexOf('?');
        if (queryStart != -1) {
            name = name.substring(0, queryStart);
        }
        return name.isEmpty() ? "download" : name;
    }

    // Retry-After: "120" (seconds). The HTTP-date form is ignored.
    static long parseRetryAfter(String header) {
        if (header == null) return 0;
        try {
            return Math.max(0, Long.parseLong(header.trim()) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // Content-Range: "bytes */12345" -> 12345
    static long parseTotalFromContentRange(String header) {
        if (header == null) return -1;
        int slash = header.lastIndexOf('/');
        if (slash == -1) return -1;
        try {
            return Long.parseLong(header.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
