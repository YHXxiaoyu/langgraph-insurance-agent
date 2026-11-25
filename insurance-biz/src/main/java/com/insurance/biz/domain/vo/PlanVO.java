package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 方案出参。不含 userId：能拿到这个对象的永远是自己名下的方案。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PlanVO(
        String id,
        String planName,
        String summary,
        BigDecimal annualPremiumBudget,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<PlanItemVO> items
) {
}