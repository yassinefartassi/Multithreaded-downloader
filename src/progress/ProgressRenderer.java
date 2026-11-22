package progress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs on its own thread and redraws one progress bar per file.
 * In a real terminal the bars update in place (ANSI codes); in an IDE console
 * a fresh snapshot is printed every second.
 */
public class ProgressRenderer implements Runnable {

    static final int BAR_WIDTH = 30;

    private final ProgressTracker tracker;
    private final boolean ansi;          // true = real terminal, can redraw in place
    private final long refreshMs;

    private volatile boolean running = true;   // written by manager thread, read by this thread

    // Only used by the renderer thread, so plain HashMap is fine here
    private final Map<String, Long> lastBytes = new HashMap<>();
    private long lastRenderTime = System.currentTimeMillis();
    private int linesPrinted = 0;

    public ProgressRenderer(ProgressTracker tracker) {
        this.tracker = tracker;
        this.ansi = System.console() != null;   // null inside IntelliJ's Run window
        this.refreshMs = ansi ? 200 : 1000;
    }

    @Override
    public void run() {
        while (running) {
            render();
            try {
                Thread.sleep(refreshMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        render(); // one last draw so the final state (100%, done/failed) is shown
    }

    public void stop() {
        running = false;
    }

    private void render() {
        long now = System.currentTimeMillis();
        double seconds = Math.max((now - lastRenderTime) / 1000.0, 0.001);
        lastRenderTime = now;

        List<String> names = new ArrayList<>(tracker.getAllFiles());
        if (names.isEmpty()) return;
        names.sort(null); // alphabetical, so lines don't jump around

        List<String> lines = new ArrayList<>();
        for (String name : names) {
            long done = tracker.getDownloaded(name);
            long total = tracker.getTotal(name);
            String status = tracker.getStatus(name);

            long previous = lastBytes.getOrDefault(name, done);
            lastBytes.put(name, done);
            double speed = Math.max(0, (done - previous) / seconds); // never negative after a restart

            lines.add(formatLine(name, done, total, status, speed));
        }

        StringBuilder out = new StringBuilder();
        if (ansi) {
            // Move the cursor up to overwrite the previous block
            if (linesPrinted > 0) {
                out.append("\033[").append(linesPrinted).append("A");
            }
            for (String line : lines) {
                out.append("\033[2K").append(line).append("\n"); // \033[2K = clear line
            }
        } else {
            // IDE console: just print a fresh snapshot
            for (String line : lines) {
                out.append(line).append("\n");
            }
            out.append("\n");
        }

        System.out.print(out);
        System.out.flush();
        linesPrinted = lines.size();
    }

    static String formatLine(String name, long done, long total, String status, double speed) {
        String shortName = name.length() > 20 ? name.substring(0, 17) + "..." : name;
        String label = String.format("%-20s", shortName);

        String tail = switch (status) {
            case ProgressTracker.DONE -> "✓ done";
            case ProgressTracker.FAILED -> "✗ failed";
            case ProgressTracker.RETRYING -> "↻ retrying";
            default -> formatBytes((long) speed) + "/s";
        };

        if (total > 0) {
            int percent = (int) Math.min(100, done * 100 / total);
            return String.format("%s %s %3d%%  %s / %s  %s",
                    label, buildBar(percent), percent, formatBytes(done), formatBytes(total), tail);
        }

        // Server didn't send Content-Length: we can't show a percentage
        return String.format("%s [%-" + BAR_WIDTH + "s]  %s  %s",
                label, " size unknown", formatBytes(done), tail);
    }

    static String buildBar(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        int filled = clamped * BAR_WIDTH / 100;
        return "[" + "#".repeat(filled) + "-".repeat(BAR_WIDTH - filled) + "]";
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
