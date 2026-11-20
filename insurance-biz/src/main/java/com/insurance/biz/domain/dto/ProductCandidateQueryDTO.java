package com.insurance.biz.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;

/**
 * 候选产品查询条件，字段名与 Python 侧工具参数一一对应。
 */
public record ProductCandidateQueryDTO(
        @NotEmpty(message = "categories 不能为空")
        List<String> categories,

        /** 最低保费上限，严格小于；不传则不限 */
        BigDecimal premiumMin,

        @Min(value = 1, message = "limitPerCategory 至少为 1")
        @Max(value = 50, message = "limitPerCategory 最多为 50")
        Integer limitPerCategory
) {

    /** 默认 5，与 Python 侧默认值保持一致 */
    public int limitOrDefault() {
        return limitPerCategory == null ? 5 : limitPerCategory;
    }
}
