package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.insurance.biz.domain.po.InsurancePlanItem;
import com.insurance.biz.domain.po.Product;

import java.math.BigDecimal;

/**
 * 方案明细出参。字段名用 snake_case，与前端方案卡片对齐
 * （前端读 id / plan_id / product_id / status 等）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PlanItemVO(
        String id,
        String planId,
        Long productId,
        String productName,
        String category,
        Integer priority,
        String recommendationReason,
        BigDecimal annualPremiumBudget,
        String status
) {

    /**
     * @param item    明细实体
     * @param product 关联产品；产品被下架或删除时为 null，前端会显示兜底文案
     */
    public static PlanItemVO from(InsurancePlanItem item, Product product) {
        return new PlanItemVO(
                item.getId(),
                item.getPlanId(),
                item.getProductId(),
                product == null ? null : product.getName(),
                product == null ? null : product.getCategory(),
                item.getPriority(),
                item.getRecommendationReason(),
                item.getAnnualPremiumBudget(),
                item.getStatus());
    }
}