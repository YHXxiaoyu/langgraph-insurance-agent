package com.insurance.biz.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 报案入参。
 * <p>
 * 只传"哪张保单、什么类型、大概多少钱、发生了什么"。
 * 归属校验在 service 里做：保单不属于当前用户就是 404。
 *
 * @param policyId    保单 id（必填，没保单不能理赔）
 * @param claimType   medical / accident / critical_illness / life
 * @param amount      申请金额，可为空（用户不清楚的时候可以先不填）
 * @param description 事故经过
 */
public record CreateClaimDTO(
        @NotBlank(message = "policyId 不能为空")
        String policyId,

        @NotBlank(message = "claimType 不能为空")
        @Pattern(regexp = "medical|accident|critical_illness|life",
                message = "claimType 只能是 medical / accident / critical_illness / life")
        String claimType,

        @DecimalMin(value = "0", message = "申请金额不能为负")
        BigDecimal amount,

        @Size(max = 1000, message = "描述最多 1000 字")
        String description
) {
}