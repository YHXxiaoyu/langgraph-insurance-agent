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

/**
 * 保险产品。对应 products 表。
 */
@Data
@TableName(value = "products", autoResultMap = true)
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 产品名称 */
    private String name;

    /** 条款文件名，如 xxx.pdf */
    private String clauseName;

    /** 险种：medical / critical_illness / accident / life */
    private String category;

    /** 承保公司 */
    private String insurer;

    private String imageUrl;
    private String description;

    /** 金额一律 BigDecimal，绝不能用 double */
    private BigDecimal minPremium;
    private BigDecimal maxPremium;

    private String targetGroup;

    /** highlights 在 MySQL 里是 JSON 列，靠 TypeHandler 转成 List */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> highlights;

    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
