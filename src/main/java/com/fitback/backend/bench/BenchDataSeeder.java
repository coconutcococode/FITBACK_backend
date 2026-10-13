package com.fitback.backend.bench;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.member.entity.LoginProvider;
import com.fitback.backend.domain.member.entity.Member;
import com.fitback.backend.domain.member.repository.MemberRepository;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.ProductRepository;
import com.fitback.backend.domain.product.service.ProductIdentityHasher;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProviderProductRef;
import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.external.shopping.shopify.ShopifyGlobalCatalogAdapter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 측정용 데이터 생성기. 회원 1명과 분석 리포트 R개를 만들고, 리포트마다 서로 다른
 * Shopify IDENTITY_ONLY 추천 상품 N개를 연결한다.
 * 생성한 reportId 목록은 bench.seed.output 파일에 쉼표로 기록해 k6 스크립트가 읽는다.
 */
@Component
@Profile("bench")
public class BenchDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchDataSeeder.class);
    private static final String SCORE_VERSION = "bench-v1";

    private final MemberRepository memberRepository;
    private final AnalysisReportRepository analysisReportRepository;
    private final ProductRepository productRepository;
    private final RecommendedItemRepository recommendedItemRepository;
    private final ProductIdentityHasher identityHasher;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final String email;
    private final String password;
    private final int itemCount;
    private final int reportCount;
    private final Path output;

    public BenchDataSeeder(
            MemberRepository memberRepository,
            AnalysisReportRepository analysisReportRepository,
            ProductRepository productRepository,
            RecommendedItemRepository recommendedItemRepository,
            ProductIdentityHasher identityHasher,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transactionTemplate,
            @Value("${bench.seed.email:bench@fitback.local}") String email,
            @Value("${bench.seed.password:BenchPass123!}") String password,
            @Value("${bench.seed.item-count:10}") int itemCount,
            @Value("${bench.seed.report-count:1}") int reportCount,
            @Value("${bench.seed.output:perf/.bench-report-id}") Path output
    ) {
        this.memberRepository = memberRepository;
        this.analysisReportRepository = analysisReportRepository;
        this.productRepository = productRepository;
        this.recommendedItemRepository = recommendedItemRepository;
        this.identityHasher = identityHasher;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.email = email;
        this.password = password;
        this.itemCount = itemCount;
        this.reportCount = reportCount;
        this.output = output;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Member member = transactionTemplate.execute(status -> findOrCreateMember());
        List<String> reportIds = new ArrayList<>();
        for (int r = 0; r < reportCount; r++) {
            Long reportId = transactionTemplate.execute(status -> seedReport(member));
            reportIds.add(String.valueOf(reportId));
        }
        Files.writeString(output, String.join(",", reportIds));
        log.info(
                "bench seed ready: reports={} itemsPerReport={} output={}",
                reportCount,
                itemCount,
                output
        );
    }

    private Member findOrCreateMember() {
        Member member = memberRepository.findByEmail(email)
                .orElseGet(() -> memberRepository.save(Member.create(
                        email,
                        "bench-" + Instant.now().toEpochMilli(),
                        passwordEncoder.encode(password),
                        LoginProvider.EMAIL
                )));
        return member;
    }

    private Long seedReport(Member member) {
        AnalysisReport report = AnalysisReport.create(
                member,
                "https://cdn.example.com/bench/report.jpg",
                50
        );
        report.markRecommendationGenerated(1, SCORE_VERSION, Instant.now());
        analysisReportRepository.save(report);

        String runId = Long.toString(System.nanoTime(), 36);
        List<ProductCategory> categories = List.of(
                ProductCategory.OUTER,
                ProductCategory.TOP,
                ProductCategory.BOTTOM
        );
        for (int i = 0; i < itemCount; i++) {
            String suffix = "bench-" + runId + "-" + i;
            ProviderProductRef ref = ProviderProductRef.stable(
                    ShopifyGlobalCatalogAdapter.PROVIDER,
                    "gid://shopify/p/" + suffix,
                    "gid://shopify/ProductVariant/" + suffix,
                    "gid://shopify/Shop/bench"
            );
            Product product = productRepository.save(Product.createIdentityOnly(
                    ref.provider(),
                    identityHasher.hash(ref),
                    ref.externalProductId(),
                    ref.externalVariantId(),
                    ref.merchantId()
            ));
            ProductCategory category = categories.get(i % categories.size());
            int rank = i / categories.size() + 1;
            recommendedItemRepository.save(RecommendedItem.create(
                    report,
                    product,
                    1,
                    rank,
                    category,
                    new BigDecimal("90.00"),
                    new BigDecimal("90.00"),
                    SCORE_VERSION,
                    List.of("PARTIAL_ATTRIBUTE_MATCH")
            ));
        }
        return report.getId();
    }
}
