package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * 理赔材料清单。
 * <p>
 * 字段名和 Python 侧返回的结构一一对应（snake_case），所以 AgentClient
 * 能直接反序列化成这个 record，不用再写一层转换。
 *
 * @param generated true = 由条款生成；false = 生成失败，用的是按险种的通用清单
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ClaimMaterialsVO(
        Long productId,
        List<String> items,
        String note,
        boolean generated
) {
}