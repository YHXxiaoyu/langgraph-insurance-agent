package com.insurance.biz.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 下单入参。
 * <p>
 * 只传"买方案里的哪几项"，产品、价格都从方案明细里取 ——
 * 让前端传价格，等于把定价权交给了浏览器。
 *
 * @param planId   方案 id
 * @param itemIds  要投保的方案明细 id，支持一次买多项
 * @param threadId 来源会话，可为空
 */
public record CreateOrderDTO(
        @NotBlank(message = "planId 不能为空")
        String planId,

        @NotEmpty(message = "itemIds 不能为空")
        @Size(max = 20, message = "一次最多投保 20 项")
        List<@NotBlank(message = "itemId 不能为空") String> itemIds,

        String threadId
) {
}