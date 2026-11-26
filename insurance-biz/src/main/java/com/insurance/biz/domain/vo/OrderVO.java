package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.insurance.biz.domain.po.OrderItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单出参。不含 userId：能拿到这个对象的永远是自己名下的订单。
 * <p>
 * updated_at 在"已支付"状态下就是支付完成的时间（订单只在这一刻被改过），
 * 所以前端可以拿它当"支付时间"显示。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderVO(
        String id,
        String orderNumber,
        String status,
        BigDecimal totalPremium,
        List<OrderItem> items,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}