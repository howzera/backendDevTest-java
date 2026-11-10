package com.similarproducts.infrastructure.productclient;

import static com.similarproducts.support.FakeProductRepository.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.similarproducts.domain.ProductNotFoundException;
import com.similarproducts.domain.ProductUnavailableException;
import com.similarproducts.support.FakeProductRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import java.time.Duration;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class CircuitBreakerProductRepositoryTest {

    private static final int THRESHOLD = 3;
    private static final Duration RESET = Duration.ofMillis(100);

    private static CircuitBreakerProductRepository withBreaker(FakeProductRepository inner) {
        return new CircuitBreakerProductRepository(inner, THRESHOLD, RESET);
    }

    private static void failTimes(CircuitBreakerProductRepository repository, String id, int times) {
        for (int i = 0; i < times; i++) {
            assertThatThrownBy(() -> repository.getProduct(id).block()).isInstanceOf(ProductUnavailableException.class);
        }
    }

    @Test
    void opensAfterConsecutiveFailuresAndFailsFast() {
        FakeProductRepository inner = new FakeProductRepository().withFailing("6");
        CircuitBreakerProductRepository repository = withBreaker(inner);

        failTimes(repository, "6", THRESHOLD + 2);

        assertThat(repository.state("product:6")).isEqualTo(State.OPEN);
        assertThat(inner.productCalls).containsExactlyElementsOf(Collections.nCopies(THRESHOLD, "6"));
        assertThatThrownBy(() -> repository.getProduct("6").block()).hasMessageContaining("circuit open");
    }

    @Test
    void successResetsTheFailureCount() {
        FakeProductRepository inner = new FakeProductRepository().withProducts("6");
        CircuitBreakerProductRepository repository = withBreaker(inner);

        inner.failing.add("6");
        failTimes(repository, "6", THRESHOLD - 1);
        inner.failing.remove("6");
        repository.getProduct("6").block();
        inner.failing.add("6");
        failTimes(repository, "6", THRESHOLD - 1);

        assertThat(repository.state("product:6")).isEqualTo(State.CLOSED);
    }

    @Test
    void trialFailureReopensAndTrialSuccessCloses() throws InterruptedException {
        FakeProductRepository inner = new FakeProductRepository().withProducts("6").withFailing("6");
        CircuitBreakerProductRepository repository = withBreaker(inner);
        failTimes(repository, "6", THRESHOLD);

        Thread.sleep(RESET.toMillis() + 50);
        failTimes(repository, "6", 1); // the single trial call fails
        assertThat(repository.state("product:6")).isEqualTo(State.OPEN);

        Thread.sleep(RESET.toMillis() + 50);
        inner.failing.remove("6");
        assertThat(repository.getProduct("6").block()).isEqualTo(product("6"));
        assertThat(repository.state("product:6")).isEqualTo(State.CLOSED);
    }

    @Test
    void oneBrokenProductDoesNotOpenTheCircuitOfOthers() {
        FakeProductRepository inner = new FakeProductRepository().withProducts("1").withFailing("6");
        CircuitBreakerProductRepository repository = withBreaker(inner);

        failTimes(repository, "6", THRESHOLD);

        assertThat(repository.getProduct("1").block()).isEqualTo(product("1"));
    }

    @Test
    void notFoundDoesNotCountAsFailure() {
        FakeProductRepository inner = new FakeProductRepository();
        CircuitBreakerProductRepository repository = withBreaker(inner);

        for (int i = 0; i < THRESHOLD + 2; i++) {
            assertThatThrownBy(() -> repository.getProduct("5").block()).isInstanceOf(ProductNotFoundException.class);
        }

        assertThat(inner.productCalls).hasSize(THRESHOLD + 2);
        assertThat(repository.state("product:5")).isEqualTo(State.CLOSED);
    }
}
