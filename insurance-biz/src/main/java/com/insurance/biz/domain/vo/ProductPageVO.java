package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * 产品分页结果。
 * 字段用 snake_case，与前端 ProductPage 类型对齐。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductPageVO(
        List<ProductVO> items,
        long page,
        long pageSize,
        long total,
        long pages
) {
}