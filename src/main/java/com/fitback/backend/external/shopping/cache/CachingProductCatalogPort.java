package com.fitback.backend.external.shopping.cache;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProductSearchResult;
import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.BatchProductCatalogPort;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 상품 상세 lookup 결과를 짧은 TTL 동안 메모리에 보관하는 catalog port 데코레이터.
 *
 * <p>검색 결과는 캐시하지 않고, 조회에 성공한 lookup 결과만 보관한다. 실패나 빈 결과는 캐시하지 않아
 * 일시적인 공급자 오류가 TTL 동안 고정되지 않게 한다. Shopify는 실시간 조회를 요구하는 공급자이므로
 * TTL은 가격·재고 변동을 감수할 수 있는 범위에서만 짧게 설정해야 한다.
 */
public final class CachingProductCatalogPort implements ProductCatalogPort, BatchProductCatalogPort {

    private final ProductCatalogPort delegate;
    private final BatchProductCatalogPort batchDelegate;
    private final Cache<ProviderProductRef, ExternalProductCandidate> cache;

    public <T extends ProductCatalogPort & BatchProductCatalogPort> CachingProductCatalogPort(
            T delegate,
            Duration ttl,
            long maximumSize
    ) {
        this(delegate, ttl, maximumSize, Ticker.systemTicker());
    }

    <T extends ProductCatalogPort & BatchProductCatalogPort> CachingProductCatalogPort(
            T delegate,
            Duration ttl,
            long maximumSize,
            Ticker ticker
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.batchDelegate = delegate;
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (maximumSize < 1) {
            throw new IllegalArgumentException("maximumSize must be positive");
        }
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .ticker(ticker)
                .recordStats()
                .build();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public ProductSearchResult search(ProductSearchQuery query) {
        return delegate.search(query);
    }

    @Override
    public Optional<ExternalProductCandidate> lookup(ProviderProductRef providerRef) {
        ExternalProductCandidate cached = cache.getIfPresent(providerRef);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<ExternalProductCandidate> loaded = delegate.lookup(providerRef);
        loaded.ifPresent(candidate -> cache.put(providerRef, candidate));
        return loaded;
    }

    @Override
    public int maxLookupBatchSize() {
        return batchDelegate.maxLookupBatchSize();
    }

    @Override
    public Map<ProviderProductRef, ExternalProductCandidate> lookupBatch(
            List<ProviderProductRef> providerRefs
    ) {
        Map<ProviderProductRef, ExternalProductCandidate> result = new LinkedHashMap<>();
        List<ProviderProductRef> misses = new ArrayList<>();
        for (ProviderProductRef providerRef : providerRefs) {
            ExternalProductCandidate cached = cache.getIfPresent(providerRef);
            if (cached != null) {
                result.put(providerRef, cached);
            } else {
                misses.add(providerRef);
            }
        }
        if (!misses.isEmpty()) {
            Map<ProviderProductRef, ExternalProductCandidate> loaded =
                    batchDelegate.lookupBatch(misses);
            cache.putAll(loaded);
            result.putAll(loaded);
        }
        return Map.copyOf(result);
    }

    public CacheStats stats() {
        return cache.stats();
    }
}
