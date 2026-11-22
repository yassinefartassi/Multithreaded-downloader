package core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5, 1_000, 10_000);

    @Test
    void delayDoublesAfterEachAttempt() {
        assertEquals(1_000, policy.delayAfterAttempt(1));
        assertEquals(2_000, policy.delayAfterAttempt(2));
        assertEquals(4_000, policy.delayAfterAttempt(3));
        assertEquals(8_000, policy.delayAfterAttempt(4));
    }

    @Test
    void delayIsCappedAtMaxDelay() {
        assertEquals(10_000, policy.delayAfterAttempt(5));
        assertEquals(10_000, policy.delayAfterAttempt(50)); // no overflow
    }

    @Test
    void serverRetryAfterIsRespectedButCapped() {
        assertEquals(5_000, policy.delayAfterAttempt(1, 5_000));   // server asks for more than backoff
        assertEquals(2_000, policy.delayAfterAttempt(2, 500));     // backoff is larger
        assertEquals(10_000, policy.delayAfterAttempt(1, 60_000)); // never above max
    }

    @Test
    void temporaryErrorsAreRetryable() {
        assertTrue(policy.isRetryableStatus(429));
        assertTrue(policy.isRetryableStatus(408));
        assertTrue(policy.isRetryableStatus(500));
        assertTrue(policy.isRetryableStatus(503));
    }

    @Test
    void permanentErrorsAreNotRetryable() {
        assertFalse(policy.isRetryableStatus(400));
        assertFalse(policy.isRetryableStatus(403));
        assertFalse(policy.isRetryableStatus(404));
        assertFalse(policy.isRetryableStatus(501));
    }

    @Test
    void rejectsZeroAttempts() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 100, 100));
    }
}
