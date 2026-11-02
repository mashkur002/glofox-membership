package com.glofox.memberships.payment;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds how long a purchase waits for the gateway, and how many charges may be
 * outstanding at once.
 *
 * <p><strong>Why.</strong> Without a bound, a hanging gateway holds one servlet
 * thread per purchase until Tomcat's pool is exhausted, and then every endpoint
 * stops answering, including {@code GET /v1/plans}. With it, a slow charge fails
 * after {@code timeout} as {@link PaymentGatewayUnavailableException}, which
 * surfaces as {@code 502 payment_gateway_error} and runs the purchase flow's
 * normal unwind (slot, claim and idempotency key released).
 *
 * <p><strong>Bulkhead.</strong> Charges run on a fixed-size pool with no queue.
 * An abandoned call keeps its thread until the gateway finally answers, so if
 * the gateway hangs for good the pool fills, and further charges are rejected
 * immediately instead of queueing behind calls that will never finish.
 *
 * <p><strong>A timeout is not a decline.</strong> The provider may still
 * complete the charge after the caller has been told 502 and its slot given
 * back. The call is not interrupted, since interrupting our thread cannot stop
 * a remote charge. It is watched instead: if it later succeeds, that is logged
 * at ERROR with the transaction id for reconciliation and refund, the same way
 * {@code PurchaseService} reports a charge whose purchase could not be saved.
 *
 * <p>Plain Java, no Spring, like the rest of the non-web layers. It owns its
 * pool, whose daemon threads expire when idle; {@link #close()} stops it
 * eagerly.
 */
public final class TimeoutPaymentGateway implements PaymentGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TimeoutPaymentGateway.class);

    private final PaymentGateway delegate;
    private final Duration timeout;
    private final ThreadPoolExecutor pool;

    public TimeoutPaymentGateway(PaymentGateway delegate, Duration timeout,
                                 int maxConcurrentCharges) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("payment timeout must be positive");
        }
        if (maxConcurrentCharges < 1) {
            throw new IllegalArgumentException("max concurrent charges must be at least 1");
        }
        this.delegate = delegate;
        this.timeout = timeout;

        AtomicInteger threadNumber = new AtomicInteger();
        // SynchronousQueue: a charge either gets a thread now or is rejected.
        // A queue would only make callers wait behind hung calls, which is the
        // failure this class exists to prevent.
        this.pool = new ThreadPoolExecutor(
                maxConcurrentCharges, maxConcurrentCharges,
                60, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread t = new Thread(runnable,
                            "payment-charge-" + threadNumber.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(true);
    }

    @Override
    public PaymentResult charge(int amountCents, String memberName) {
        CompletableFuture<PaymentResult> call;
        try {
            call = CompletableFuture.supplyAsync(
                    () -> delegate.charge(amountCents, memberName), pool);
        } catch (RejectedExecutionException saturated) {
            throw new PaymentGatewayUnavailableException(
                    "payment gateway saturated: " + pool.getMaximumPoolSize()
                    + " charges already in flight");
        }

        try {
            return call.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException slow) {
            watchForLateSuccess(call, amountCents);
            throw new PaymentGatewayUnavailableException(
                    "payment gateway did not answer within " + timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            watchForLateSuccess(call, amountCents);
            throw new PaymentGatewayUnavailableException(
                    "interrupted while waiting for the payment gateway");
        } catch (ExecutionException failed) {
            // The delegate's own answer, unchanged: a decline must stay a 402,
            // not become a 502 because it travelled through another thread.
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new PaymentGatewayUnavailableException("payment gateway failed");
        }
    }

    /** Charges currently holding a pool thread, including abandoned ones. */
    int inFlight() {
        return pool.getActiveCount();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    private static void watchForLateSuccess(CompletableFuture<PaymentResult> call,
                                            int amountCents) {
        // The member name is deliberately not logged: it is caller-controlled,
        // and the transaction id is what reconciliation needs.
        call.whenComplete((result, failure) -> {
            if (result != null) {
                log.error("charge completed after the caller was told it failed (timeout); "
                          + "no purchase was recorded; requires reconciliation and refund: "
                          + "transaction={} amountCents={}",
                          forLog(result.transactionId()), amountCents);
            }
        });
    }

    /** The transaction id comes from the gateway; keep it to one log line. */
    private static String forLog(String raw) {
        return raw == null ? "null" : raw.replaceAll("[\\r\\n\\p{Cntrl}]", "_");
    }
}
