package model;

public class DownloadResult {

    private final String fileName;
    private final String url;
    private final boolean success;
    private final long bytesDownloaded;
    private final long timeInMillis;
    private final String errorMessage;
    private final int attempts;

    public DownloadResult(String fileName, String url, boolean success,
                          long bytesDownloaded, long timeInMillis, String errorMessage, int attempts) {
        this.fileName = fileName;
        this.url = url;
        this.success = success;
        this.bytesDownloaded = bytesDownloaded;
        this.timeInMillis = timeInMillis;
        this.errorMessage = errorMessage;
        this.attempts = attempts;
    }

    public String getFileName() { return fileName; }
    public String getUrl() { return url; }
    public boolean isSuccess() { return success; }
    public long getBytesDownloaded() { return bytesDownloaded; }
    public long getTimeInMillis() { return timeInMillis; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttempts() { return attempts; }
}
