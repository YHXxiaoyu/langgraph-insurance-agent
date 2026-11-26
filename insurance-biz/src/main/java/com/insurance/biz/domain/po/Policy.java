package com.insurance.biz.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 保单。对应 policies 表。
 * <p>
 * 保单只在「支付成功」那一刻产生：把方案明细标记为已投保的同一次事务里插入一条，
 * 所以 policies 表里不会有未付款的草稿。
 */
@Data
@TableName("policies")
public class Policy {

    /**
     * 主键由方案项 id 派生（见 PolicyServiceImpl#policyIdOf），不是随机 UUID。
     * <p>
     * 好处是同一个方案项永远对应同一个主键：用户重复点支付、网络重试，
     * 算出来还是这个 id，直接命中已有记录，不需要再设计额外的去重字段。
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属用户。所有查询都必须带上这个条件，否则就是越权 */
    private Long userId;

    /** 保的是哪个产品 */
    private Long productId;

    /** 保单号，给用户看的那个号；数据库上有唯一索引 */
    private String policyNumber;

    /** 保单状态：pending 待生效 / active 保障中 / expired 已到期 / terminated 已终止 */
    private String status;

    /** 生效时间，目前就是支付成功的时间 */
    private LocalDateTime effectiveAt;

    /** 失效时间 */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}