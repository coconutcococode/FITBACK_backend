package com.fitback.backend.bench;

import com.fitback.backend.domain.product.service.port.ProductCatalogPort;
import com.fitback.backend.external.shopping.cache.CachingProductCatalogPort;
import com.fitback.backend.external.shopping.shopify.ShopifyGlobalCatalogAdapter;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 성능 측정 전용 설정. bench 프로필에서만 켜지며 운영 설정에는 영향을 주지 않는다.
 *
 * <ul>
 *     <li>per-item: batch lookup 도입 이전처럼 상품마다 lookup 호출</li>
 *     <li>batch: 현재 운영 코드와 같은 묶음 조회</li>
 *     <li>batch-cache: 묶음 조회 + 짧은 TTL 메모리 캐시</li>
 * </ul>
 */
@Configuration
@Profile("bench")
public class BenchCatalogConfig {

    private static final Logger log = LoggerFactory.getLogger(BenchCatalogConfig.class);

    @Bean
    @Primary
    public ProductCatalogPort benchProductCatalogPort(
            ShopifyGlobalCatalogAdapter adapter,
            @Value("${bench.catalog-mode:batch}") String mode,
            @Value("${bench.cache-ttl:PT30S}") Duration cacheTtl
    ) {
        log.info("bench catalog mode={} cacheTtl={}", mode, cacheTtl);
        return switch (mode) {
            case "per-item" -> new PerItemProductCatalogPort(adapter);
            case "batch" -> adapter;
            case "batch-cache" -> new CachingProductCatalogPort(adapter, cacheTtl, 10_000);
            default -> throw new IllegalArgumentException("unknown bench.catalog-mode: " + mode);
        };
    }
}
