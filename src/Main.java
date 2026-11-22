import core.DownloadManager;
import model.DownloadResult;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class Main {

    private static final List<String> DEFAULT_URLS = List.of(
            "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf",
            "https://upload.wikimedia.org/wikipedia/commons/3/3f/Fronalpstock_big.jpg",
            "https://proof.ovh.net/files/10Mb.dat",
            "https://httpbin.org/image/png",
            "https://example.com/this-file-does-not-exist.zip"
    );

    public static void main(String[] args) {
        // URLs can be passed on the command line; otherwise use the demo list
        List<String> urls = args.length > 0 ? Arrays.asList(args) : DEFAULT_URLS;

        System.out.println("Downloading " + urls.size() + " files...\n");

        DownloadManager manager = new DownloadManager(3, Path.of("downloads"));

        long start = System.currentTimeMillis();
        List<DownloadResult> results = manager.downloadAll(urls);
        long totalTime = System.currentTimeMillis() - start;

        System.out.println("\n===== Summary =====");
        int ok = 0;
        for (DownloadResult r : results) {
            printResult(r);
            if (r.isSuccess()) ok++;
        }

        System.out.println("\n" + ok + " succeeded, " + (results.size() - ok)
                + " failed, total time: " + totalTime + " ms");
    }

    private static void printResult(DownloadResult r) {
        String attempts = r.getAttempts() > 1 ? " | " + r.getAttempts() + " attempts" : "";
        if (r.isSuccess()) {
            System.out.println("✅ " + r.getFileName()
                    + " | " + r.getBytesDownloaded() + " bytes"
                    + " | " + r.getTimeInMillis() + " ms" + attempts);
        } else {
            System.out.println("❌ " + r.getFileName()
                    + " | error: " + r.getErrorMessage() + attempts);
        }
    }
}
