package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 「标记已投保」的出参：方案的最新状态 + 刚发出的那张保单。
 * <p>
 * 支付页拿到后可以直接把保单号显示出来，不用再发一次请求去猜"哪张是刚买的"。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MarkInsuredVO(
        PlanVO plan,
        PolicyVO policy
) {
}