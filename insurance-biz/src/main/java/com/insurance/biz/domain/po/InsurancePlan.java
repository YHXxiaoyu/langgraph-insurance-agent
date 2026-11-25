package com.insurance.biz.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 保险方案。对应 insurance_plans 表。
 * <p>
 * 方案由 Agent 在对话里生成，用户点「确认保存方案」之后才由 Python 调内部接口落库。
 * 没确认的方案只存在于 LangGraph 的 state 里，不写这张表——这样业务库里只有
 * 「用户认可的方案」，不会堆满试来试去的草稿。
 */
@Data
@TableName(value = "insurance_plans", autoResultMap = true)
public class InsurancePlan {

    /** UUID 字符串，由 Java 侧生成 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属用户。所有查询必须带上这个条件，否则就是越权 */
    private Long userId;

    /** 方案名，例如「为你整理的推荐方案」 */
    private String planName;

    /** 方案说明，给用户看的一段话 */
    private String summary;

    /**
     * 被保人画像：年龄、职业、预算、关注险种等，结构由 Agent 决定。
     * 存 JSON 列而不是拆成固定字段，因为对话里抽到的信息每轮都不一样。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> insuredProfile;

    /** 年预算，允许为空（用户没提预算时） */
    private BigDecimal annualPremiumBudget;

    /** 方案状态：uninsured 未投保 / applying 投保中 / insured 已投保 */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
