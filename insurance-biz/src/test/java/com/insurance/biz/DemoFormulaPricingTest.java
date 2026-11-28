package com.insurance.biz;

import com.insurance.biz.domain.dto.PremiumQuoteDTO;
import com.insurance.biz.domain.po.Product;
import com.insurance.biz.service.pricing.DemoFormulaPricing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * demo_formula 定价公式的单元测试。
 * <p>
 * 这个类能直接测，是因为它被刻意写成了纯函数：不碰数据库、不碰 Spring、没有状态。
 * 公式里每一个系数只要有人改，这里的用例就会立刻报出来 —— 这是它存在的意义。
 */
@DisplayName("demo_formula 定价公式")
class DemoFormulaPricingTest {

    private static Product product(String category, String minPremium) {
        Product product = new Product();
        product.setCategory(category);
        product.setMinPremium(minPremium == null ? null : new BigDecimal(minPremium));
        return product;
    }

    private static PremiumQuoteDTO quote(int age, String gender, String coverage, String socialSecurity) {
        return new PremiumQuoteDTO(1L, age, gender, new BigDecimal(coverage), socialSecurity, null);
    }

    @Test
    @DisplayName("重疾险 30 岁男性 50 万：240 × 1.000 × 0.85 × 1.05 = 214.20")
    void 重疾险标准算例() {
        var breakdown = DemoFormulaPricing.calculate(
                product("critical_illness", "240.00"),
                quote(30, "male", "500000", null));

        assertThat(breakdown.totalPremium()).isEqualByComparingTo("214.20");
        assertThat(breakdown.basePremium()).isEqualByComparingTo("240");
        assertThat(breakdown.coverageFactor()).isEqualByComparingTo("1.0000");
        assertThat(breakdown.ageFactor()).isEqualByComparingTo("0.85");
        assertThat(breakdown.genderFactor()).isEqualByComparingTo("1.05");
        // 重疾险不看社保，系数恒为 1
        assertThat(breakdown.socialSecurityFactor()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("医疗险保额低于参考保额时，保额系数被抬到下限 0.5")
    void 医疗险保额系数被夹到下限() {
        // 医疗险参考保额 300 万，填 50 万 → 0.167，必须被抬到 0.5
        var breakdown = DemoFormulaPricing.calculate(
                product("medical", "255.00"),
                quote(30, "male", "500000", "yes"));

        assertThat(breakdown.coverageFactor()).isEqualByComparingTo("0.5");
        // 解释文案要说清楚"被夹了"，否则用户拿计算器一按就发现对不上
        assertThat(String.join("", breakdown.explain())).contains("按上下限取");
    }

    @Test
    @DisplayName("医疗险无社保比有社保贵 30%")
    void 医疗险无社保更贵() {
        BigDecimal withSecurity = DemoFormulaPricing.calculate(
                product("medical", "300.00"),
                quote(30, "male", "3000000", "yes")).totalPremium();
        BigDecimal withoutSecurity = DemoFormulaPricing.calculate(
                product("medical", "300.00"),
                quote(30, "male", "3000000", "no")).totalPremium();

        // 不能直接比 "有社保 × 1.3"：公式最后会四舍五入到分，
        // 例如 267.75 × 1.3 = 348.075 会被算成 348.08。比比值才是真正的业务规则。
        BigDecimal ratio = withoutSecurity.divide(withSecurity, 4, java.math.RoundingMode.HALF_UP);
        assertThat(ratio).isEqualByComparingTo("1.30");
    }

    @Test
    @DisplayName("同样参数算两次，结果完全一致")
    void 结果确定() {
        Product product = product("critical_illness", "3185.00");
        PremiumQuoteDTO dto = quote(35, "female", "800000", null);

        BigDecimal first = DemoFormulaPricing.calculate(product, dto).totalPremium();
        BigDecimal second = DemoFormulaPricing.calculate(product, dto).totalPremium();

        assertThat(first).isEqualByComparingTo(second);
    }

    @Test
    @DisplayName("年龄分段边界：35 岁（1.00）与 36 岁（1.25）落在不同档")
    void 年龄分段边界() {
        var at35 = DemoFormulaPricing.calculate(
                product("critical_illness", "1000.00"), quote(35, "male", "500000", null));
        var at36 = DemoFormulaPricing.calculate(
                product("critical_illness", "1000.00"), quote(36, "male", "500000", null));

        assertThat(at35.ageFactor()).isEqualByComparingTo("1.00");
        assertThat(at36.ageFactor()).isEqualByComparingTo("1.25");
        assertThat(at36.totalPremium()).isGreaterThan(at35.totalPremium());
    }

    @Test
    @DisplayName("产品没配最低保费时用 100 兜底，不抛空指针")
    void 基准保费兜底() {
        var breakdown = DemoFormulaPricing.calculate(
                product("life", null),
                quote(30, "male", "1000000", null));

        assertThat(breakdown.basePremium()).isEqualByComparingTo("100");
        assertThat(breakdown.totalPremium()).isPositive();
    }
}
