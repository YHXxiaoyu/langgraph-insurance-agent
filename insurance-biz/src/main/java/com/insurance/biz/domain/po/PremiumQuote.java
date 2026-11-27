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
 * 保费试算记录。对应 premium_quotes 表。
 * <p>
 * 每一次试算都留一条，入参和结果都存 JSON —— 用户看得见的每一个系数都能回溯，
 * 将来纠纷时能回答"这个价格当时是怎么算出来的"。
 * 报价有有效期（expires_at），下单时用来判断价格还能不能用。
 */
@Data
@TableName(value = "premium_quotes", autoResultMap = true)
public class PremiumQuote {

    /** UUID 字符串，由 Java 侧生成 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 试算的用户；表里允许为空，但对外接口走的都是登录态，所以实际总有值 */
    private Long userId;

    /** 试算的产品 */
    private Long productId;

    /** 入参快照：年龄、性别、保额、社保、职业类别 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> requestParams;

    /** 结果快照：基准保费、各个系数、合计、解释文案 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> result;

    /** 合计年保费；单独存一列是为了能排序、汇总，不用解析 JSON */
    private BigDecimal totalPremium;

    /**
     * 定价方式：table 查费率表 / demo_formula 本地公式 /
     * mock_adapter 模拟保司接口 / insurer_api 真实保司接口 / local_fallback 兜底。
     * <p>
     * 当前只实现 demo_formula —— 费率表里还没有数据，这一点在返回给前端时会明确标注。
     */
    private String pricingMode;

    /** 报价有效期，过了这个时间下单要重新试算 */
    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}