package com.glofox.memberships.config;

import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.payment.TimeoutPaymentGateway;
import com.glofox.memberships.service.PurchaseFactory;
import com.glofox.memberships.service.PurchaseService;
import com.glofox.memberships.service.StartDatePolicy;
import com.glofox.memberships.store.IdempotencyStore;
import com.glofox.memberships.store.InventoryStore;
import com.glofox.memberships.store.PlanCatalogue;
import com.glofox.memberships.store.PurchaseStore;
import com.glofox.memberships.store.UnlimitedClaimStore;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the service layer, which carries no Spring annotations of its own.
 *
 * <p>Public so slice tests can {@code @Import} it, for the same compile-time
 * reason as {@link SeedingConfig}.
 */
@Configuration
public class ServiceConfig {

    @Bean
    StartDatePolicy startDatePolicy(Clock clock) {
        return new StartDatePolicy(clock);
    }

    @Bean
    PurchaseFactory purchaseFactory(Clock clock) {
        return new PurchaseFactory(clock);
    }

    /**
     * {@code PaymentGateway} resolves to the vendor's component, as shipped, and
     * is wrapped here in a {@link TimeoutPaymentGateway} so a hanging gateway
     * cannot exhaust the servlet threads.
     *
     * <p>The wrapper is deliberately not a bean. As a second
     * {@code PaymentGateway} bean it would make the type ambiguous for anything
     * that injects the port. Its pool threads are daemons that expire when
     * idle, so it needs no shutdown hook.
     */
    @Bean
    PurchaseService purchaseService(PlanCatalogue catalogue,
                                    InventoryStore inventory,
                                    UnlimitedClaimStore claims,
                                    PurchaseStore purchases,
                                    IdempotencyStore idempotency,
                                    PaymentGateway gateway,
                                    StartDatePolicy startDates,
                                    PurchaseFactory factory,
                                    @Value("${memberships.payment.timeout}") Duration paymentTimeout,
                                    @Value("${memberships.payment.max-concurrent-charges}")
                                    int maxConcurrentCharges) {
        PaymentGateway bounded =
                new TimeoutPaymentGateway(gateway, paymentTimeout, maxConcurrentCharges);
        return new PurchaseService(catalogue, inventory, claims, purchases, idempotency,
                                   bounded, startDates, factory);
    }
}
