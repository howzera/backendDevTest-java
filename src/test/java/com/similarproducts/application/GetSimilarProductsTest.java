package com.similarproducts.application;

import static com.similarproducts.support.FakeProductRepository.product;
import static org.assertj.core.api.Assertions.assertThat;

import com.similarproducts.domain.ProductDetail;
import com.similarproducts.domain.ProductNotFoundException;
import com.similarproducts.domain.ProductUnavailableException;
import com.similarproducts.support.FakeProductRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class GetSimilarProductsTest {

    private static final Duration TIMEOUT = Duration.ofMillis(200);

    private static GetSimilarProducts useCase(FakeProductRepository repository) {
        return new GetSimilarProducts(repository, TIMEOUT);
    }

    private static List<String> ids(List<ProductDetail> products) {
        return products.stream().map(ProductDetail::id).toList();
    }

    @Test
    void returnsSimilarProductsInSimilarityOrder() {
        FakeProductRepository repository = new FakeProductRepository()
                .withSimilar("1", "3", "2", "4")
                .withProducts("2", "3", "4")
                // The first one is the slowest: order must not depend on arrival time.
                .withDelay("3", Duration.ofMillis(50));

        assertThat(ids(useCase(repository).execute("1").block())).containsExactly("3", "2", "4");
    }

    @Test
    void returnsEmptyListWhenProductHasNoSimilarProducts() {
        FakeProductRepository repository = new FakeProductRepository().withSimilar("1");

        assertThat(useCase(repository).execute("1").block()).isEmpty();
    }

    @Test
    void failsWithNotFoundWhenProductDoesNotExist() {
        StepVerifier.create(useCase(new FakeProductRepository()).execute("999"))
                .expectError(ProductNotFoundException.class)
                .verify();
    }

    @Test
    void failsWithUnavailableWhenSimilarIdsSourceFails() {
        FakeProductRepository repository = new FakeProductRepository();
        repository.failingSimilarIds.add("1");

        StepVerifier.create(useCase(repository).execute("1"))
                .expectError(ProductUnavailableException.class)
                .verify();
    }

    @Test
    void skipsSimilarProductThatDoesNotExist() {
        // k6 "notFound" scenario: product 4 -> [1, 2, 5] and 5 returns 404.
        FakeProductRepository repository = new FakeProductRepository().withSimilar("4", "1", "2", "5").withProducts("1", "2");

        assertThat(ids(useCase(repository).execute("4").block())).containsExactly("1", "2");
    }

    @Test
    void skipsSimilarProductWhoseSourceFails() {
        // k6 "error" scenario: product 5 -> [1, 2, 6] and 6 returns 500.
        FakeProductRepository repository = new FakeProductRepository()
                .withSimilar("5", "1", "2", "6")
                .withProducts("1", "2", "6")
                .withFailing("6");

        assertThat(ids(useCase(repository).execute("5").block())).containsExactly("1", "2");
    }

    @Test
    void skipsSimilarProductSlowerThanTimeout() {
        // k6 "slow"/"verySlow" scenarios: some similar products take seconds.
        FakeProductRepository repository = new FakeProductRepository()
                .withSimilar("2", "3", "100")
                .withProducts("3", "100")
                .withDelay("100", Duration.ofSeconds(5));

        long started = System.nanoTime();
        List<ProductDetail> result = useCase(repository).execute("2").block();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(ids(result)).containsExactly("3");
        assertThat(elapsed).isLessThan(TIMEOUT.plusMillis(300));
    }

    @Test
    void fetchesDetailsConcurrently() {
        FakeProductRepository repository = new FakeProductRepository()
                .withSimilar("1", "2", "3", "4")
                .withProducts("2", "3", "4")
                .withDelay("2", Duration.ofMillis(150))
                .withDelay("3", Duration.ofMillis(150))
                .withDelay("4", Duration.ofMillis(150));

        long started = System.nanoTime();
        useCase(repository).execute("1").block();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // Sequential would take ~450 ms; concurrent takes ~150 ms.
        assertThat(elapsed).isLessThan(Duration.ofMillis(350));
    }

    @Test
    void removesDuplicatedSimilarIds() {
        FakeProductRepository repository = new FakeProductRepository().withSimilar("1", "2", "3", "2").withProducts("2", "3");

        assertThat(useCase(repository).execute("1").block()).containsExactly(product("2"), product("3"));
    }
}
