package com.insurance.biz.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 投保订单。对应 orders 表。
 * <p>
 * 订单是"一次支付行为"的记录：先下单（pending_payment），再支付（paid）。
 * 保单只在支付成功时才产生，所以订单和保单的关系是 1 : N（一个订单可以买多个产品）。
 */
@Data
@TableName(value = "orders", autoResultMap = true)
public class Order {

    /** UUID 字符串，由 Java 侧生成 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属用户，所有查询必须带上 */
    private Long userId;

    /** 来源会话，方便"这单是从哪次咨询聊出来的"，不设外键 */
    private String threadId;

    /** 订单号，给用户看的；数据库有唯一索引 */
    private String orderNumber;

    /** 订单项数组，存 JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<OrderItem> items;

    /** 订单总保费 = 各项 premium 之和 */
    private BigDecimal totalPremium;

    /**
     * 订单状态：pending_payment 待支付 / pending_policy 待出单 /
     * paid 已支付 / completed 已完成 / cancelled 已取消。
     * <p>
     * 当前实现只用到 pending_payment → paid；pending_policy / completed 留给
     * "支付成功但保险公司还没回单"这类真实场景。
     */
    private String status;

    /** 下单时的方案快照，避免方案后续被改动导致订单对不上 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> recommendationSnapshot;

    /**
     * 本单引用了哪几次试算记录。
     * <p>
     * 有它才能回答"这笔钱是按什么价算的"：点开 quote 能看到当时的入参、系数和有效期。
     * 没走试算的老订单是空数组。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> quoteIds;

    /**
     * 幂等键：同一用户对同一个方案的同一批明细重复下单时，算出来是同一个值，
     * 数据库唯一索引会挡住第二次插入，接口直接把已有订单返回。
     */
    private String idempotencyKey;

    /** 幂等指纹，幂等键的摘要，排查"为什么被判成重复"时用 */
    private String idempotencyFingerprint;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}