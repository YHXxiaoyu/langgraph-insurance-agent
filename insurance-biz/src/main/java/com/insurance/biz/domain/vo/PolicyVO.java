package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.insurance.biz.domain.po.Policy;
import com.insurance.biz.domain.po.Product;

import java.time.LocalDateTime;

/**
 * 保单出参。
 * <p>
 * 不含 userId：能拿到这个对象的永远是自己名下的保单。
 * 产品名、险种、公司名不在 policies 表里，是查产品表补进来的，
 * 否则前端只能显示一个 product_id。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PolicyVO(
        String id,
        String policyNumber,
        Long productId,
        String productName,
        String category,
        String insurer,
        String status,
        LocalDateTime effectiveAt,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {

    /**
     * @param policy  保单实体
     * @param product 关联产品；产品被下架或删除时为 null，前端显示兜底文案
     */
    public static PolicyVO from(Policy policy, Product product) {
        return new PolicyVO(
                policy.getId(),
                policy.getPolicyNumber(),
                policy.getProductId(),
                product == null ? null : product.getName(),
                product == null ? null : product.getCategory(),
                product == null ? null : product.getInsurer(),
                policy.getStatus(),
                policy.getEffectiveAt(),
                policy.getExpiresAt(),
                policy.getCreatedAt());
    }
}