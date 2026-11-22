package core;

/**
 * Decides whether a failed download should be retried, and how long to wait first.
 * Uses exponential backoff: baseDelay, 2 x baseDelay, 4 x baseDelay ... capped at maxDelay.
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;

    public RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    /** 3 attempts, waiting 1s then 2s, never more than 10s. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 1_000, 10_000);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Temporary server problems are worth retrying; client errors like 404 are not.
     * 429 = Too Many Requests, 408 = Request Timeout, 5xx = server errors (except 501 Not Implemented).
     */
    public boolean isRetryableStatus(int status) {
        return status == 429
                || status == 408
                || (status >= 500 && status <= 599 && status != 501);
    }

    /** Delay before the next try, after the given attempt (1-based) failed. */
    public long delayAfterAttempt(int attempt) {
        long delay = baseDelayMs * (1L << Math.min(attempt - 1, 30));
        return Math.min(delay, maxDelayMs);
    }

    /** If the server sent a Retry-After header, respect it (but never wait longer than maxDelay). */
    public long delayAfterAttempt(int attempt, long serverRetryAfterMs) {
        long backoff = delayAfterAttempt(attempt);
        return Math.min(Math.max(backoff, serverRetryAfterMs), maxDelayMs);
    }
}
