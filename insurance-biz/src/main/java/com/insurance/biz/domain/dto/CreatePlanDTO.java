package com.insurance.biz.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 方案落库入参。由 Python 在用户确认方案后调用内部接口传入。
 * <p>
 * userId 由 Python 从原始的对话请求里带过来——内部接口没有登录态，
 * 拿不到 SecurityContext，这一点和对外接口正好相反。
 */
public record CreatePlanDTO(

        @NotNull(message = "userId 不能为空")
        Long userId,

        @NotBlank(message = "planName 不能为空")
        @Size(max = 120, message = "planName 最多 120 字")
        String planName,

        String summary,

        @DecimalMin(value = "0", message = "annualPremiumBudget 不能为负")
        BigDecimal annualPremiumBudget,

        /** 被保人画像，结构由 Agent 决定，Java 只做透传存储 */
        Map<String, Object> insuredProfile,

        @NotEmpty(message = "方案至少要有一个产品")
        @Valid
        List<PlanItemDTO> items
) {

    /** 方案里的一条明细 */
    public record PlanItemDTO(

            @NotNull(message = "productId 不能为空")
            Long productId,

            /** 为空时按数组顺序自动编号（1 开始） */
            Integer priority,

            String recommendationReason,

            @DecimalMin(value = "0", message = "annualPremiumBudget 不能为负")
            BigDecimal annualPremiumBudget
    ) {
    }
}