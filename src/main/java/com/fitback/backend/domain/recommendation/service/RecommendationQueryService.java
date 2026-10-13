package com.fitback.backend.domain.recommendation.service;

import com.fitback.backend.domain.analysis.entity.AnalysisReport;
import com.fitback.backend.domain.analysis.repository.AnalysisReportRepository;
import com.fitback.backend.domain.analysis.service.RecommendationResultProvider;
import com.fitback.backend.domain.product.dto.ProductDetailResponse;
import com.fitback.backend.domain.product.entity.Product;
import com.fitback.backend.domain.product.repository.SavedProductRepository;
import com.fitback.backend.domain.product.service.ProductDetailBatchResult;
import com.fitback.backend.domain.product.service.ProductDetailService;
import com.fitback.backend.domain.product.service.ProductResponseMapper;
import com.fitback.backend.domain.product.service.model.ProductAvailability;
import com.fitback.backend.domain.product.service.model.ProductCategory;
import com.fitback.backend.domain.product.service.model.ProductStorageMode;
import com.fitback.backend.domain.recommendation.dto.RecommendationGroupResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationItemResponse;
import com.fitback.backend.domain.recommendation.dto.RecommendationResultResponse;
import com.fitback.backend.domain.recommendation.entity.RecommendationStatus;
import com.fitback.backend.domain.recommendation.entity.RecommendedItem;
import com.fitback.backend.domain.recommendation.repository.RecommendedItemRepository;
import com.fitback.backend.global.exception.BusinessException;
import com.fitback.backend.global.exception.ErrorCode;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class RecommendationQueryService implements RecommendationResultProvider {

    private final AnalysisReportRepository analysisReportRepository;
    private final RecommendedItemRepository recommendedItemRepository;
    private final ProductResponseMapper productResponseMapper;
    private final ProductDetailService productDetailService;
    private final SavedProductRepository savedProductRepository;
    private final TransactionTemplate readOnlyTransaction;

    public RecommendationQueryService(
            AnalysisReportRepository analysisReportRepository,
            RecommendedItemRepository recommendedItemRepository,
            ProductResponseMapper productResponseMapper,
            ProductDetailService productDetailService,
            SavedProductRepository savedProductRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.analysisReportRepository = analysisReportRepository;
        this.recommendedItemRepository = recommendedItemRepository;
        this.productResponseMapper = productResponseMapper;
        this.productDetailService = productDetailService;
        this.savedProductRepository = savedProductRepository;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @Override
    public RecommendationResultResponse findFor(AnalysisReport report) {
        StoredRecommendation stored = readOnlyTransaction.execute(status -> {
            List<RecommendedItem> items = findItems(report);
            Long memberId = items.isEmpty() ? null : report.getMember().getId();
            return new StoredRecommendation(report, items, findSavedProductIds(memberId, items));
        });
        return result(stored);
    }

    public RecommendationResultResponse findByReportId(Long memberId, Long reportId) {
        StoredRecommendation stored = readOnlyTransaction.execute(status -> {
            AnalysisReport report = analysisReportRepository
                    .findByIdAndMemberIdAndDeletedAtIsNull(reportId, memberId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.ANALYSIS_REPORT_NOT_FOUND
                    ));
            List<RecommendedItem> items = findItems(report);
            return new StoredRecommendation(report, items, findSavedProductIds(memberId, items));
        });
        return result(stored);
    }

    private List<RecommendedItem> findItems(AnalysisReport report) {
        return report.getRecommendationGeneratedAt() == null
                ? List.of()
                : recommendedItemRepository
                        .findByReportIdOrderByCategoryAscRankNoAsc(report.getId());
    }

    // DB에서 읽을 값은 위의 짧은 트랜잭션에서 모두 꺼내 두고, 외부 공급자 조회는 트랜잭션과
    // 영속성 컨텍스트 밖에서 실행해 응답을 기다리는 동안 DB 커넥션을 쥐고 있지 않는다.
    private RecommendationResultResponse result(StoredRecommendation stored) {
        AnalysisReport report = stored.report();
        List<RecommendedItem> items = stored.items();
        RecommendationStatus status = status(report);
        Set<Long> savedProductIds = stored.savedProductIds();
        Set<String> warnings = new TreeSet<>();
        List<RecommendedItem> responseOrderedItems = responseOrderItems(items);
        ProductDetailBatchResult batchResult = productDetailService.lookupIdentityOnlyDetails(
                responseOrderedItems.stream()
                        .map(RecommendedItem::getProduct)
                        .toList()
        );
        List<RecommendationGroupResponse> groups = java.util.Arrays.stream(ProductCategory.values())
                .map(category -> new RecommendationGroupResponse(
                        category,
                        responseOrderedItems.stream()
                                .filter(item -> item.getCategory() == category)
                                .map(item -> toResponse(
                                        item,
                                        savedProductIds.contains(item.getProduct().getId()),
                                        warnings,
                                        batchResult
                                ))
                                .toList()
                ))
                .toList();
        return new RecommendationResultResponse(
                status,
                report.getResultScoreVersion(),
                groups,
                !warnings.isEmpty(),
                List.copyOf(warnings)
        );
    }

    private Set<Long> findSavedProductIds(
            Long memberId,
            List<RecommendedItem> items
    ) {
        List<Long> productIds = items.stream()
                .map(item -> item.getProduct().getId())
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(savedProductRepository.findSavedProductIds(
                memberId,
                productIds
        ));
    }

    private RecommendationItemResponse toResponse(
            RecommendedItem item,
            boolean saved,
            Set<String> warnings,
            ProductDetailBatchResult batchResult
    ) {
        Product product = item.getProduct();
        if (product.getStorageMode() == ProductStorageMode.IDENTITY_ONLY) {
            ProductDetailResponse batchDetail = batchResult.detailsByProductId()
                    .get(product.getId());
            if (batchDetail != null) {
                return responseWithDetail(item, saved, batchDetail);
            }
            ErrorCode batchFailure = batchResult.failuresByProductId().get(product.getId());
            if (batchFailure != null) {
                warnings.add(batchFailure.getCode());
                return unresolvedResponse(item, saved);
            }
            try {
                ProductDetailResponse detail = productDetailService.getDetail(product.getId());
                return responseWithDetail(item, saved, detail);
            } catch (BusinessException exception) {
                warnings.add(exception.getErrorCode().getCode());
                return unresolvedResponse(item, saved);
            }
        }
        return new RecommendationItemResponse(
                product.getId(),
                item.getRankNo(),
                product.getImageUrl(),
                product.getName(),
                product.getSellerName(),
                productResponseMapper.price(product),
                product.getPurchaseUrl(),
                item.getSimilarityScore(),
                item.getFinalScore(),
                item.getReasonCodeList(),
                product.getAvailability(),
                saved
        );
    }

    private static List<RecommendedItem> responseOrderItems(List<RecommendedItem> items) {
        return java.util.Arrays.stream(ProductCategory.values())
                .flatMap(category -> items.stream()
                        .filter(item -> item.getCategory() == category)
                        .sorted(Comparator.comparing(RecommendedItem::getRankNo)))
                .toList();
    }

    private static RecommendationItemResponse responseWithDetail(
            RecommendedItem item,
            boolean saved,
            ProductDetailResponse detail
    ) {
        Product product = item.getProduct();
        return new RecommendationItemResponse(
                product.getId(),
                item.getRankNo(),
                detail.imageUrl(),
                detail.name(),
                detail.sellerName(),
                detail.price(),
                detail.purchaseUrl(),
                item.getSimilarityScore(),
                item.getFinalScore(),
                item.getReasonCodeList(),
                detail.availability(),
                saved
        );
    }

    private static RecommendationItemResponse unresolvedResponse(
            RecommendedItem item,
            boolean saved
    ) {
        Product product = item.getProduct();
        return new RecommendationItemResponse(
                product.getId(),
                item.getRankNo(),
                null,
                null,
                null,
                null,
                null,
                item.getSimilarityScore(),
                item.getFinalScore(),
                item.getReasonCodeList(),
                ProductAvailability.TEMPORARILY_UNRESOLVED,
                saved
        );
    }

    private static RecommendationStatus status(AnalysisReport report) {
        if (report.getRecommendationGeneratedAt() == null) {
            return RecommendationStatus.NOT_GENERATED;
        }
        return report.hasRecommendationInputRevision(report.getResultInputRevision())
                ? RecommendationStatus.CURRENT
                : RecommendationStatus.STALE;
    }

    private record StoredRecommendation(
            AnalysisReport report,
            List<RecommendedItem> items,
            Set<Long> savedProductIds
    ) {
    }
}
