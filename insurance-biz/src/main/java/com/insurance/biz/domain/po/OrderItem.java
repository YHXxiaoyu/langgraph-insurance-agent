package com.insurance.biz.domain.po;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 订单项。
 * <p>
 * 注意它<b>不是一张独立的表</b>：orders.items 是一个 JSON 数组，这个类就是数组里的一个元素。
 * 之所以不拆成 order_items 表，是因为订单项的内容完全跟着订单走 —— 订单不删、项也不会单独改，
 * 存成 JSON 少一次关联查询。带 @JsonNaming 是为了存进 JSON 和返回前端时都用 snake_case。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class OrderItem {

    /** 这笔订单来自哪个方案 */
    private String planId;

    /** 方案明细 id：支付成功后靠它把明细标记为已投保 */
    private String planItemId;

    private Long productId;

    /** 下单时的产品名快照：产品以后改名，订单里也该显示当时买的是什么 */
    private String productName;

    /** 这一项的年保费 */
    private BigDecimal premium;

    /** 方案里的展示顺序 */
    private Integer priority;
}