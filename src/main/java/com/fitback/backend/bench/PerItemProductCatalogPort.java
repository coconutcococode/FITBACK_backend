package com.fitback.backend.bench;

import com.fitback.backend.domain.product.service.model.ExternalProductCandidate;
import com.fitback.backend.domain.product.service.model.ProductSearchQuery;
import com.fitback.backend.domain.product.service.model.ProductSearchResult;
import com.fitback.backend.domain.product.service.model.ProviderCapabilities;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import java.util.Objects;
import java.util.Optional;

/**
 * BatchProductCatalogPort를 노출하지 않는 래퍼.
 *
 * <p>ProductDetailService는 port가 BatchProductCatalogPort일 때만 묶음 조회를 사용하므로, 이 래퍼를
 * 쓰면 batch lookup 도입(a9e775e) 이전처럼 상품마다 lookup을 한 번씩 호출하는 경로가 재현된다.
 */
final class PerItemProductCatalogPort implements ProductCatalogPort {

    private final ProductCatalogPort delegate;

    PerItemProductCatalogPort(ProductCatalogPort delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
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
        return delegate.lookup(providerRef);
    }
}
