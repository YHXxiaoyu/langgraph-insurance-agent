package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.insurance.biz.domain.po.Claim;
import com.insurance.biz.domain.po.Policy;
import com.insurance.biz.domain.po.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 理赔出参。
 * <p>
 * 保单号和产品名不在 claims 表里，是查保单、产品补进来的 ——
 * 用户看理赔记录时，最先想确认的就是"哪张保单、什么产品"。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ClaimVO(
        String id,
        String claimNumber,
        String policyId,
        String policyNumber,
        Long productId,
        String productName,
        String claimType,
        BigDecimal amount,
        String description,
        String status,
        LocalDateTime submittedAt,
        LocalDateTime updatedAt
) {

    /**
     * @param claim   理赔实体
     * @param policy  关联保单；理论上不会为 null（外键约束），保险起见做空值保护
     * @param product 关联产品，可能为空
     */
    public static ClaimVO from(Claim claim, Policy policy, Product product) {
        return new ClaimVO(
                claim.getId(),
                claim.getClaimNumber(),
                claim.getPolicyId(),
                policy == null ? null : policy.getPolicyNumber(),
                claim.getProductId(),
                product == null ? null : product.getName(),
                claim.getClaimType(),
                claim.getAmount(),
                claim.getDescription(),
                claim.getStatus(),
                claim.getSubmittedAt(),
                claim.getUpdatedAt());
    }
}