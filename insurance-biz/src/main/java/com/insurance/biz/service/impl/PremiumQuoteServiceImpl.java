package com.insurance.biz.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.insurance.biz.common.exception.BadRequestException;
import com.insurance.biz.common.exception.NotFoundException;
import com.insurance.biz.domain.dto.PremiumQuoteDTO;
import com.insurance.biz.domain.po.PremiumQuote;
import com.insurance.biz.domain.po.Product;
import com.insurance.biz.domain.vo.PremiumQuoteVO;
import com.insurance.biz.domain.vo.PricingFormVO;
import com.insurance.biz.mapper.PremiumQuoteMapper;
import com.insurance.biz.mapper.ProductMapper;
import com.insurance.biz.service.IPremiumQuoteService;
import com.insurance.biz.service.pricing.DemoFormulaPricing;
import com.insurance.biz.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PremiumQuoteServiceImpl extends ServiceImpl<PremiumQuoteMapper, PremiumQuote>
        implements IPremiumQuoteService {

    /** 当前的定价方式。接入真实费率表后改成 table，并在这里分流 */
    private static final String MODE_DEMO_FORMULA = "demo_formula";

    /** 报价有效期：一天。过了就让用户重新试算 */
    private static final int VALID_HOURS = 24;

    private final ProductMapper productMapper;

    @Override
    public PremiumQuoteVO quote(PremiumQuoteDTO dto) {
        // 1.产品必须存在且在售，否则算出来的价格毫无意义
        Product product = productMapper.selectById(dto.productId());
        if (product == null) {
            throw new NotFoundException("产品不存在");
        }
        if (!"active".equals(product.getStatus())) {
            throw new BadRequestException("该产品已下架，无法试算");
        }

        // 2.按 demo_formula 算一遍，拿到每一项系数
        DemoFormulaPricing.Breakdown breakdown =
                DemoFormulaPricing.calculate(product, dto);

        // 3.入参和结果都存快照：以后要回答"这个价格怎么来的"，全靠这两列
        PremiumQuote quote = new PremiumQuote();
        quote.setId(UUID.randomUUID().toString());
        quote.setUserId(UserContext.getUserId());
        quote.setProductId(product.getId());
        quote.setRequestParams(paramsOf(dto));
        quote.setResult(resultOf(breakdown));
        quote.setTotalPremium(breakdown.totalPremium());
        quote.setPricingMode(MODE_DEMO_FORMULA);
        quote.setExpiresAt(LocalDateTime.now().plusHours(VALID_HOURS));
        save(quote);

        // 4.created_at 是数据库默认值，回查一次
        return PremiumQuoteVO.from(getById(quote.getId()), product);
    }

    @Override
    public PremiumQuoteVO getMine(String quoteId) {
        PremiumQuote quote = lambdaQuery()
                .eq(PremiumQuote::getId, quoteId)
                .eq(PremiumQuote::getUserId, UserContext.getUserId())
                .one();
        if (quote == null) {
            throw new NotFoundException("试算记录不存在");
        }
        return PremiumQuoteVO.from(quote, productMapper.selectById(quote.getProductId()));
    }

    @Override
    public PricingFormVO pricingForm(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new NotFoundException("产品不存在");
        }

        // 字段定义里带着默认值，前端打开表单就是"30 岁、50 万保额、有社保"，用户改就行
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("age", "被保人年龄", "number", true, 30, Map.of(
                "min", 0, "max", 120, "unit", "岁")));
        fields.add(field("gender", "性别", "select", true, "male", Map.of(
                "options", List.of(
                        Map.of("value", "male", "label", "男"),
                        Map.of("value", "female", "label", "女"),
                        Map.of("value", "other", "label", "其他")))));
        fields.add(field("coverage_amount", "保额", "number", true, 500000, Map.of(
                "min", 10000,
                "max", 50000000,
                "unit", "元",
                "step", 10000)));
        // 社保只对医疗险有意义，其他险种的表单里不出现这个字段
        if ("medical".equals(product.getCategory())) {
            fields.add(field("social_security", "是否有医保", "select", false, "yes", Map.of(
                    "options", List.of(
                            Map.of("value", "yes", "label", "有"),
                            Map.of("value", "no", "label", "无")))));
        }
        fields.add(field("occupation_class", "职业类别", "text", false, "", Map.of(
                "placeholder", "如 1-3 类，可不填")));

        return new PricingFormVO(
                MODE_DEMO_FORMULA,
                product.getId(),
                product.getName(),
                "CNY",
                fields,
                "当前为演示费率：基于产品最低保费与年龄、保额等因素计算，非保险公司精算结果。");
    }

    /** 表单字段的通用结构：key / 中文标签 / 控件类型 / 是否必填 / 默认值 / 额外约束 */
    private Map<String, Object> field(String key, String label, String type, boolean required,
                                      Object defaultValue, Map<String, Object> extra) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", key);
        field.put("label", label);
        field.put("type", type);
        field.put("required", required);
        field.put("default", defaultValue);
        field.putAll(extra);
        return field;
    }

    /** 入参快照 */
    private Map<String, Object> paramsOf(PremiumQuoteDTO dto) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("age", dto.age());
        params.put("gender", dto.gender());
        params.put("coverage_amount", dto.coverageAmount());
        params.put("social_security", dto.socialSecurity());
        params.put("occupation_class", dto.occupationClass());
        return params;
    }

    /** 结果快照：系数拆开存，前端能逐步展示；将来换定价方式时这里的键名要保持稳定 */
    private Map<String, Object> resultOf(DemoFormulaPricing.Breakdown breakdown) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base_premium", breakdown.basePremium());
        result.put("coverage_factor", breakdown.coverageFactor());
        result.put("age_factor", breakdown.ageFactor());
        result.put("gender_factor", breakdown.genderFactor());
        result.put("social_security_factor", breakdown.socialSecurityFactor());
        result.put("total_premium", breakdown.totalPremium());
        result.put("explain", breakdown.explain());
        return result;
    }
}