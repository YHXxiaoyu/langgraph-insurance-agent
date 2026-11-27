package com.insurance.biz.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 理赔申请。对应 claims 表。
 * <p>
 * 一条理赔必须挂在一张保单上（policy_id）：没买过的东西赔不了。
 * product_id 是从保单上冗余过来的，方便按产品统计，不参与业务判断。
 */
@Data
@TableName("claims")
public class Claim {

    /** UUID 字符串，由 Java 侧生成 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属用户，所有查询必须带上 */
    private Long userId;

    /** 冗余自保单的产品 id，方便按产品统计 */
    private Long productId;

    /** 关联保单。理赔能不能报，全看这张保单的状态 */
    private String policyId;

    /** 理赔单号，给用户和客服查的；数据库有唯一索引 */
    private String claimNumber;

    /** 理赔类型：medical 医疗 / accident 意外 / critical_illness 重疾 / life 身故 */
    private String claimType;

    /**
     * 金额。用户报案时填的是<b>申请金额</b>，审核通过后才变成保司核定的赔付金额 ——
     * 两种情况共用这一列，靠 status 区分当前含义。
     */
    private BigDecimal amount;

    /** 事故经过、就诊医院等描述 */
    private String description;

    /** 状态：submitted 已提交 / reviewing 审核中 / approved 已通过 / rejected 已拒赔 / paid 已赔付 */
    private String status;

    /** 提交时间，数据库默认值 */
    private LocalDateTime submittedAt;

    private LocalDateTime updatedAt;
}