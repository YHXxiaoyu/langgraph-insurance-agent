package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.insurance.biz.domain.po.PremiumQuote;
import com.insurance.biz.domain.po.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 试算出参。
 * <p>
 * breakdown 里是每一步系数和解释文案，前端直接把 explain 那几行显示出来，
 * 用户就能看懂"为什么是这个价"。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PremiumQuoteVO(
        String id,
        Long productId,
        String productName,
        String category,
        BigDecimal totalPremium,
        String pricingMode,
        Map<String, Object> breakdown,
        Map<String, Object> params,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {

    public static PremiumQuoteVO from(PremiumQuote quote, Product product) {
        return new PremiumQuoteVO(
                quote.getId(),
                quote.getProductId(),
                product == null ? null : product.getName(),
                product == null ? null : product.getCategory(),
                quote.getTotalPremium(),
                quote.getPricingMode(),
                quote.getResult(),
                quote.getRequestParams(),
                quote.getExpiresAt(),
                quote.getCreatedAt());
    }
}