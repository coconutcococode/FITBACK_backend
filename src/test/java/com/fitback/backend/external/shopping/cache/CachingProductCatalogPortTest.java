package com.fitback.backend.external.shopping.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProductSearchResult;
import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.BatchProductCatalogPort;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CachingProductCatalogPortTest {

    private final FakeCatalog delegate = new FakeCatalog();
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final CachingProductCatalogPort port =
            new CachingProductCatalogPort(delegate, Duration.ofSeconds(30), 100, ticker);

    @Test
    void 같은_상품의_반복_lookup은_TTL_동안_공급자를_다시_호출하지_않는다() {
        ProviderProductRef ref = ref("1");

        port.lookup(ref);
        port.lookup(ref);

        assertThat(delegate.lookupCalls).isEqualTo(1);
    }

    @Test
    void TTL이_지나면_공급자를_다시_호출한다() {
        ProviderProductRef ref = ref("1");

        port.lookup(ref);
        nanos.addAndGet(Duration.ofSeconds(31).toNanos());
        port.lookup(ref);

        assertThat(delegate.lookupCalls).isEqualTo(2);
    }

    @Test
    void 묶음_조회는_캐시에_없는_상품만_공급자에_요청한다() {
        port.lookupBatch(List.of(ref("1"), ref("2")));

        Map<ProviderProductRef, ExternalProductCandidate> result =
                port.lookupBatch(List.of(ref("1"), ref("2"), ref("3")));

        assertThat(result).containsOnlyKeys(ref("1"), ref("2"), ref("3"));
        assertThat(delegate.batchRequests).containsExactly(
                List.of(ref("1"), ref("2")),
                List.of(ref("3"))
        );
    }

    @Test
    void 공급자가_찾지_못한_상품은_캐시하지_않는다() {
        delegate.missing.add("404");

        port.lookup(ref("404"));
        port.lookup(ref("404"));

        assertThat(delegate.lookupCalls).isEqualTo(2);
    }

    @Test
    void 모든_상품이_캐시에_있으면_묶음_조회를_호출하지_않는다() {
        port.lookupBatch(List.of(ref("1"), ref("2")));

        port.lookupBatch(List.of(ref("2"), ref("1")));

        assertThat(delegate.batchRequests).hasSize(1);
    }

    private static ProviderProductRef ref(String id) {
        return ProviderProductRef.stable("shopify", "product-" + id, "variant-" + id, "shop");
    }

    private static final class FakeCatalog implements ProductCatalogPort, BatchProductCatalogPort {

        private int lookupCalls;
        private final List<List<ProviderProductRef>> batchRequests = new ArrayList<>();
        private final Set<String> missing = new HashSet<>();

        @Override
        public ProviderCapabilities capabilities() {
            return new ProviderCapabilities(
                    "shopify", false, true, true, true, true, true,
                    false, false, true, null, true
            );
        }

        @Override
        public ProductSearchResult search(ProductSearchQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ExternalProductCandidate> lookup(ProviderProductRef providerRef) {
            lookupCalls++;
            return found(providerRef) ? Optional.of(candidate(providerRef)) : Optional.empty();
        }

        @Override
        public int maxLookupBatchSize() {
            return 50;
        }

        @Override
        public Map<ProviderProductRef, ExternalProductCandidate> lookupBatch(
                List<ProviderProductRef> providerRefs
        ) {
            batchRequests.add(List.copyOf(providerRefs));
            Map<ProviderProductRef, ExternalProductCandidate> result = new LinkedHashMap<>();
            providerRefs.stream()
                    .filter(this::found)
                    .forEach(ref -> result.put(ref, candidate(ref)));
            return result;
        }

        private boolean found(ProviderProductRef ref) {
            return !missing.contains(ref.externalProductId().replace("product-", ""));
        }

        private static ExternalProductCandidate candidate(ProviderProductRef ref) {
            return new ExternalProductCandidate(
                    ref,
                    "상품 " + ref.externalProductId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    Instant.parse("2026-10-13T00:00:00Z")
            );
        }
    }
}
