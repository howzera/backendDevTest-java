package com.similarproducts.infrastructure.productclient;

import static com.similarproducts.support.FakeProductRepository.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.similarproducts.domain.ProductNotFoundException;
import com.similarproducts.domain.ProductUnavailableException;
import com.similarproducts.support.FakeProductRepository;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CachedProductRepositoryTest {

    private static final Duration TTL = Duration.ofSeconds(300);
    private static final Duration NOT_FOUND_TTL = Duration.ofSeconds(60);

    /** Controllable time source for TTL tests. */
    private final AtomicLong nanos = new AtomicLong();

    private CachedProductRepository cached(FakeProductRepository inner) {
        return new CachedProductRepository(inner, TTL, NOT_FOUND_TTL, 1_000, nanos::get);
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void secondCallIsServedFromCache() {
        FakeProductRepository inner = new FakeProductRepository().withProducts("1");
        CachedProductRepository repository = cached(inner);

        repository.getProduct("1").block();
        repository.getProduct("1").block();

        assertThat(inner.productCalls).containsExactly("1");
    }

    @Test
    void similarIdsAreCachedToo() {
        FakeProductRepository inner = new FakeProductRepository().withSimilar("1", "2");
        CachedProductRepository repository = cached(inner);

        assertThat(repository.getSimilarIds("1").block()).containsExactly("2");
        assertThat(repository.getSimilarIds("1").block()).containsExactly("2");
        assertThat(inner.similarCalls).containsExactly("1");
    }

    @Test
    void concurrentCallsShareOneUpstreamCall() {
        FakeProductRepository inner = new FakeProductRepository().withProducts("1").withDelay("1", Duration.ofMillis(50));
        CachedProductRepository repository = cached(inner);

        Long results = Flux.range(0, 200).flatMap(i -> repository.getProduct("1"), 200).count().block();

        assertThat(results).isEqualTo(200);
        assertThat(inner.productCalls).containsExactly("1");
    }

    @Test
    void entryExpiresAfterTtl() {
        FakeProductRepository inner = new FakeProductRepository().withProducts("1");
        CachedProductRepository repository = cached(inner);

        repository.getProduct("1").block();
        advance(TTL.plusSeconds(1));
        repository.getProduct("1").block();

        assertThat(inner.productCalls).containsExactly("1", "1");
    }

    @Test
    void notFoundIsCachedWithItsOwnTtl() {
        FakeProductRepository inner = new FakeProductRepository();
        CachedProductRepository repository = cached(inner);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> repository.getProduct("5").block()).isInstanceOf(ProductNotFoundException.class);
        }
        assertThat(inner.productCalls).containsExactly("5");

        advance(NOT_FOUND_TTL.plusSeconds(1));
        assertThatThrownBy(() -> repository.getProduct("5").block()).isInstanceOf(ProductNotFoundException.class);
        assertThat(inner.productCalls).containsExactly("5", "5");
    }

    @Test
    void failuresAreNotCached() {
        FakeProductRepository inner = new FakeProductRepository().withFailing("6");
        CachedProductRepository repository = cached(inner);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> repository.getProduct("6").block()).isInstanceOf(ProductUnavailableException.class);
        }

        assertThat(inner.productCalls).containsExactly("6", "6");
    }

    @Test
    void slowCallKeepsRunningAfterCallerTimeoutAndIsCached() throws InterruptedException {
        FakeProductRepository inner = new FakeProductRepository().withProducts("100").withDelay("100", Duration.ofMillis(100));
        CachedProductRepository repository = cached(inner);

        StepVerifier.create(repository.getProduct("100").timeout(Duration.ofMillis(10)))
                .expectError(TimeoutException.class)
                .verify();

        Thread.sleep(200); // the detached upstream call finishes in the background
        StepVerifier.create(repository.getProduct("100").timeout(Duration.ofMillis(10)))
                .expectNext(product("100"))
                .verifyComplete();
        assertThat(inner.productCalls).containsExactly("100");
    }

    @Test
    void callerSubscribingLaterStillGetsTheValue() {
        FakeProductRepository inner = new FakeProductRepository().withProducts("1");
        CachedProductRepository repository = cached(inner);
        Mono<?> lazy = repository.getProduct("1");

        assertThat(inner.productCalls).isEmpty(); // nothing happens until subscription
        assertThat(lazy.block()).isEqualTo(product("1"));
    }
}
