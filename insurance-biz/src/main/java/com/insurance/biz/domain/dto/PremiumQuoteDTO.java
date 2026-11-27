package com.insurance.biz.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * 试算入参。
 * <p>
 * 只收"影响价格的因素"，产品价格本身从 products 表取 —— 和下单不让前端传价是同一个道理。
 *
 * @param productId        产品 id
 * @param age              被保人年龄
 * @param gender           male / female / other
 * @param coverageAmount   保额（元）
 * @param socialSecurity   是否有社保 yes / no，只有医疗险会用到
 * @param occupationClass  职业类别，接口只记录、当前不参与定价
 */
public record PremiumQuoteDTO(
        @NotNull(message = "productId 不能为空")
        Long productId,

        @NotNull(message = "年龄不能为空")
        @Min(value = 0, message = "年龄不能小于 0")
        @Max(value = 120, message = "年龄不能大于 120")
        Integer age,

        @NotBlank(message = "性别不能为空")
        @Pattern(regexp = "male|female|other", message = "性别只能是 male / female / other")
        String gender,

        @NotNull(message = "保额不能为空")
        @DecimalMin(value = "10000", message = "保额至少 1 万")
        @DecimalMax(value = "50000000", message = "保额过大")
        BigDecimal coverageAmount,

        @Pattern(regexp = "yes|no", message = "socialSecurity 只能是 yes / no")
        String socialSecurity,

        String occupationClass
) {
}