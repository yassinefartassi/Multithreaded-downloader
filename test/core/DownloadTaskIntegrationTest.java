package core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import model.DownloadResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import progress.ProgressTracker;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs DownloadTask against a tiny HTTP server inside the test,
 * so retry and resume behaviour can be checked without the internet.
 */
class DownloadTaskIntegrationTest {

    private static final byte[] DATA = randomBytes(100_000);
    private static final RetryPolicy FAST_RETRY = new RetryPolicy(3, 10, 50); // keep tests fast

    private HttpServer server;
    private String baseUrl;
    private final List<String> rangeHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger missingHits = new AtomicInteger();
    private final AtomicInteger flakyHits = new AtomicInteger();
    private final AtomicInteger dropHits = new AtomicInteger();

    @TempDir
    Path outputDir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // Normal file that supports Range requests
        server.createContext("/file.bin", exchange -> {
            rangeHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Range")));
            serveWithRange(exchange);
        });

        // Always 404
        server.createContext("/missing.zip", exchange -> {
            missingHits.incrementAndGet();
            sendEmpty(exchange, 404);
        });

        // 503 the first time, then OK
        server.createContext("/flaky.bin", exchange -> {
            if (flakyHits.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                sendEmpty(exchange, 503);
            } else {
                sendFull(exchange);
            }
        });

        // Server that ignores Range and always sends the whole file
        server.createContext("/norange.bin", this::sendFull);

        // First request: promises the full length, then cuts the connection halfway
        server.createContext("/drop.bin", exchange -> {
            rangeHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Range")));
            if (dropHits.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(200, DATA.length);
                OutputStream body = exchange.getResponseBody();
                body.write(DATA, 0, DATA.length / 2);
                body.flush();
                exchange.close(); // connection dropped before the end
            } else {
                serveWithRange(exchange);
            }
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsFullFile() throws IOException {
        DownloadResult result = task("/file.bin").call();

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(1, result.getAttempts());
        assertEquals(DATA.length, result.getBytesDownloaded());
        assertArrayEquals(DATA, Files.readAllBytes(outputDir.resolve("file.bin")));
        assertFalse(Files.exists(outputDir.resolve("file.bin.part")));
    }

    @Test
    void doesNotRetryPermanentErrors() {
        DownloadResult result = task("/missing.zip").call();

        assertFalse(result.isSuccess());
        assertEquals("HTTP 404", result.getErrorMessage());
        assertEquals(1, result.getAttempts());
        assertEquals(1, missingHits.get());
    }

    @Test
    void retriesTemporaryErrorsThenSucceeds() throws IOException {
        DownloadResult result = task("/flaky.bin").call();

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(2, result.getAttempts());
        assertArrayEquals(DATA, Files.readAllBytes(outputDir.resolve("flaky.bin")));
    }

    @Test
    void resumesFromExistingPartFile() throws IOException {
        int half = DATA.length / 2;
        Files.write(outputDir.resolve("file.bin.part"), Arrays.copyOf(DATA, half));

        DownloadResult result = task("/file.bin").call();

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("bytes=" + half + "-", rangeHeaders.get(0)); // asked only for the rest
        assertArrayEquals(DATA, Files.readAllBytes(outputDir.resolve("file.bin")));
    }

    @Test
    void alreadyCompletePartFileIsJustRenamed() throws IOException {
        Files.write(outputDir.resolve("file.bin.part"), DATA);

        DownloadResult result = task("/file.bin").call(); // server answers 416 "nothing left to send"

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertArrayEquals(DATA, Files.readAllBytes(outputDir.resolve("file.bin")));
    }

    @Test
    void restartsWhenServerIgnoresRange() throws IOException {
        Files.write(outputDir.resolve("norange.bin.part"), new byte[]{1, 2, 3});

        DownloadResult result = task("/norange.bin").call();

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertArrayEquals(DATA, Files.readAllBytes(outputDir.resolve("norange.bin")));
    }

    @Test
    void droppedConnectionIsRetriedAndResumed() throws IOException {
        DownloadResult result = task("/drop.bin").call();

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(2, result.getAttempts());
        assertTrue(rangeHeaders.get(1).startsWith("bytes="), "second attempt should resume");
        assertArrayEquals(DATA, Files.readAllBytes(outputDir.resolve("drop.bin")));
    }

    @Test
    void managerDownloadsSeveralFilesInParallel() throws IOException {
        DownloadManager manager = new DownloadManager(3, outputDir, FAST_RETRY, false);

        List<DownloadResult> results = manager.downloadAll(List.of(
                baseUrl + "/file.bin", baseUrl + "/norange.bin", baseUrl + "/missing.zip"));

        assertEquals(3, results.size());
        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());
        assertFalse(results.get(2).isSuccess());
        assertArrayEquals(DATA, Files.readAllBytes(outputDir.resolve("file.bin")));
    }

    // ---------- helpers ----------

    private DownloadTask task(String path) {
        return new DownloadTask(baseUrl + path, outputDir, new ProgressTracker(), FAST_RETRY);
    }

    private static void serveWithRange(HttpExchange exchange) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range == null) {
            sendBytes(exchange, 200, DATA, 0);
            return;
        }
        int start = Integer.parseInt(range.replace("bytes=", "").replace("-", ""));
        if (start >= DATA.length) {
            exchange.getResponseHeaders().add("Content-Range", "bytes */" + DATA.length);
            sendEmpty(exchange, 416);
            return;
        }
        exchange.getResponseHeaders().add("Content-Range",
                "bytes " + start + "-" + (DATA.length - 1) + "/" + DATA.length);
        sendBytes(exchange, 206, DATA, start);
    }

    private void sendFull(HttpExchange exchange) throws IOException {
        sendBytes(exchange, 200, DATA, 0);
    }

    private static void sendBytes(HttpExchange exchange, int status, byte[] data, int from) throws IOException {
        int length = data.length - from;
        exchange.sendResponseHeaders(status, length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(data, from, length);
        }
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }
}
