package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * 试算表单定义。
 * <p>
 * 现在来自 demo_formula 的固定模板（product_pricing_schemas 还没有数据）。
 * 将来接入真实产品配置时，只需换数据源，前端按 fields 渲染的逻辑不用改 ——
 * 这也是原设计里 product_pricing_schemas 那张表存在的意义。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PricingFormVO(
        String pricingMode,
        Long productId,
        String productName,
        String currency,
        List<Map<String, Object>> fields,
        String note
) {
}