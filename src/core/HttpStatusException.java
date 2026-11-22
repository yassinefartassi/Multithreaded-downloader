package core;

import java.io.IOException;

/** Thrown when the server answers with an HTTP status we can't use (404, 429, 503...). */
public class HttpStatusException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final long retryAfterMs; // from the Retry-After header, 0 if absent

    public HttpStatusException(int status, long retryAfterMs) {
        super("HTTP " + status);
        this.status = status;
        this.retryAfterMs = retryAfterMs;
    }

    public int getStatus() {
        return status;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
