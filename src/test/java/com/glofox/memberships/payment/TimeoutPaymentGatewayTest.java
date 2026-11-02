package com.glofox.memberships.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.glofox.memberships.payment.PaymentGateway.PaymentDeclinedException;
import com.glofox.memberships.payment.PaymentGateway.PaymentGatewayUnavailableException;
import com.glofox.memberships.payment.PaymentGateway.PaymentResult;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The timeout decorator: a hanging gateway becomes a fast 502, and a charge
 * that lands after we gave up is reported for reconciliation, not lost.
 */
class TimeoutPaymentGatewayTest {

    private static final Duration TIMEOUT = Duration.ofMillis(100);

    private final CountDownLatch release = new CountDownLatch(1);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TimeoutPaymentGateway.class);
    private TimeoutPaymentGateway gateway;

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanUp() {
        release.countDown();
        logger.detachAppender(logs);
        if (gateway != null) {
            gateway.close();
        }
    }

    /** A delegate that blocks until the test releases it, then succeeds. */
    private PaymentGateway hanging() {
        return (amount, name) -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new PaymentResult("txn-late");
        };
    }

    @Test
    @DisplayName("a prompt charge is returned unchanged")
    void promptChargePassesThrough() {
        gateway = new TimeoutPaymentGateway((a, n) -> new PaymentResult("txn-1"), TIMEOUT, 4);

        assertThat(gateway.charge(100, "Alice").transactionId()).isEqualTo("txn-1");
    }

    @Test
    @DisplayName("a decline from the delegate is rethrown as-is, not turned into an outage")
    void declineIsNotRewrapped() {
        gateway = new TimeoutPaymentGateway((a, n) -> {
            throw new PaymentDeclinedException("card declined");
        }, TIMEOUT, 4);

        assertThatExceptionOfType(PaymentDeclinedException.class)
                .isThrownBy(() -> gateway.charge(100, "Alice"))
                .withMessage("card declined");
    }

    @Test
    @DisplayName("a hanging charge fails as unavailable once the timeout passes")
    void hangingChargeTimesOut() {
        gateway = new TimeoutPaymentGateway(hanging(), TIMEOUT, 4);

        long start = System.nanoTime();
        assertThatExceptionOfType(PaymentGatewayUnavailableException.class)
                .isThrownBy(() -> gateway.charge(100, "Alice"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).isGreaterThanOrEqualTo(TIMEOUT.toMillis()).isLessThan(2_000);
    }

    @Test
    @DisplayName("when every charge slot is held, a new charge fails fast instead of queueing")
    void saturatedPoolFailsFast() {
        gateway = new TimeoutPaymentGateway(hanging(), Duration.ofSeconds(30), 1);
        Thread holder = new Thread(() -> {
            try {
                gateway.charge(100, "Holder");
            } catch (RuntimeException expected) {
                // released or shut down at cleanup
            }
        });
        holder.start();
        waitUntil(() -> gateway.inFlight() == 1);

        long start = System.nanoTime();
        assertThatExceptionOfType(PaymentGatewayUnavailableException.class)
                .isThrownBy(() -> gateway.charge(100, "Alice"));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                .as("rejected immediately, not after the 30s timeout")
                .isLessThan(1_000);
    }

    @Test
    @DisplayName("a charge that succeeds after the timeout is logged at ERROR for reconciliation")
    void lateSuccessIsReported() {
        gateway = new TimeoutPaymentGateway(hanging(), TIMEOUT, 4);
        assertThatExceptionOfType(PaymentGatewayUnavailableException.class)
                .isThrownBy(() -> gateway.charge(100, "Alice"));

        release.countDown();
        waitUntil(() -> logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR));

        ILoggingEvent event = logs.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
        assertThat(event.getFormattedMessage())
                .contains("txn-late")
                .as("the member name is caller-controlled and never logged")
                .doesNotContain("Alice");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.onSpinWait();
        }
    }
}
