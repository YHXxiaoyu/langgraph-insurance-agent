package com.insurance.biz.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 产品列表出参。
 * <p>
 * 相对 Product 实体去掉了 createdAt / updatedAt —— 前端用不上，
 * 也避免把「数据什么时候入库的」这类内部信息暴露出去。
 */
@Data
public class ProductVO {

    private Long id;
    private String name;
    private String clauseName;
    private String category;
    private String insurer;
    private String imageUrl;
    private String description;
    private BigDecimal minPremium;
    private BigDecimal maxPremium;
    private String targetGroup;
    private List<String> highlights;
    private String status;
}
