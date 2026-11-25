package com.insurance.biz.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 保险方案明细。对应 insurance_plan_items 表。
 * <p>
 * 一条明细 = 方案里的一个产品。同一方案内 product_id 唯一
 * （数据库有 uk_plan_items_plan_product 约束），避免 Agent 把同一个产品推荐两遍。
 */
@Data
@TableName("insurance_plan_items")
public class InsurancePlanItem {

    /** UUID 字符串，由 Java 侧生成 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属方案。方案删除时数据库级联删除明细 */
    private String planId;

    /** 推荐的产品 */
    private Long productId;

    /** 展示顺序，从 1 开始；越小越靠前 */
    private Integer priority;

    /** 为什么推荐它，直接来自 Agent 的推荐理由 */
    private String recommendationReason;

    /** 这个产品的年保费预算参考 */
    private BigDecimal annualPremiumBudget;

    /**
     * 明细状态：uninsured 未投保 / insured 已投保。
     * <p>
     * 注意和方案级状态不是一回事：方案是 applying（投保中）时，
     * 明细可能一部分 insured、一部分还是 uninsured。
     */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}