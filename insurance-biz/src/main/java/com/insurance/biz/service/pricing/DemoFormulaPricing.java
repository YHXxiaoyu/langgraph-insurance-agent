package com.insurance.biz.service.pricing;

import com.insurance.biz.domain.dto.PremiumQuoteDTO;
import com.insurance.biz.domain.po.Product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * demo_formula 定价公式。
 * <p>
 * 没有真实费率表时用的本地规则：可解释、可复现、每次结果一样。
 * 它<b>不是</b>精算费率 —— 系数是编的，方向对、能讲清楚。
 * 接入真实费率表后，这个类会被 table 模式的实现替代，接口形状不变。
 * <p>
 * 公式：年保费 = 基准保费 × 保额系数 × 年龄系数 × 性别系数 × 社保系数
 */
public final class DemoFormulaPricing {

    private DemoFormulaPricing() {
    }

    /** 各险种的"参考保额"：保费按保额相对这个值的比例缩放 */
    private static final Map<String, BigDecimal> REFERENCE_COVERAGE = Map.of(
            "medical", new BigDecimal("3000000"),
            "critical_illness", new BigDecimal("500000"),
            "life", new BigDecimal("1000000"),
            "accident", new BigDecimal("500000"));

    private static final BigDecimal DEFAULT_REFERENCE = new BigDecimal("500000");

    /** 年龄分段系数：年龄段上限 + 系数，顺序从前到后匹配 */
    private static final int[] AGE_UPPER = {17, 25, 30, 35, 40, 45, 50, 55, 60, 200};
    private static final BigDecimal[] AGE_FACTOR = {
            new BigDecimal("0.60"), new BigDecimal("0.70"), new BigDecimal("0.85"),
            new BigDecimal("1.00"), new BigDecimal("1.25"), new BigDecimal("1.60"),
            new BigDecimal("2.00"), new BigDecimal("2.60"), new BigDecimal("3.40"),
            new BigDecimal("4.50")};

    /** 保额系数的上下限：防止用户填一个离谱的保额把价格算飞 */
    private static final BigDecimal MIN_COVERAGE_FACTOR = new BigDecimal("0.5");
    private static final BigDecimal MAX_COVERAGE_FACTOR = new BigDecimal("3.0");

    /**
     * 计算结果。每个系数都单独返回，前端可以直接把算式展示给用户看。
     *
     * @param basePremium  基准年保费
     * @param totalPremium 合计年保费
     * @param explain      面向用户的解释，一行一条
     */
    public record Breakdown(
            BigDecimal basePremium,
            BigDecimal coverageFactor,
            BigDecimal ageFactor,
            BigDecimal genderFactor,
            BigDecimal socialSecurityFactor,
            BigDecimal totalPremium,
            List<String> explain
    ) {
    }

    public static Breakdown calculate(Product product, PremiumQuoteDTO dto) {
        List<String> explain = new ArrayList<>();

        // 1.基准保费：取产品最低价，作为"1 倍保额、30 岁、标准性别"的年保费
        BigDecimal base = product.getMinPremium() == null
                ? new BigDecimal("100")
                : product.getMinPremium();
        explain.add("基准年保费 ¥" + base.setScale(2, RoundingMode.HALF_UP) + "（产品最低保费）");

        // 2.保额系数 = 保额 / 该险种的参考保额
        BigDecimal reference = REFERENCE_COVERAGE.getOrDefault(
                String.valueOf(product.getCategory()), DEFAULT_REFERENCE);
        BigDecimal rawCoverageFactor = dto.coverageAmount()
                .divide(reference, 4, RoundingMode.HALF_UP);
        BigDecimal coverageFactor = rawCoverageFactor
                .max(MIN_COVERAGE_FACTOR)
                .min(MAX_COVERAGE_FACTOR);
        if (coverageFactor.compareTo(rawCoverageFactor) == 0) {
            explain.add("保额 ¥" + plain(dto.coverageAmount())
                    + " ÷ 参考保额 ¥" + plain(reference)
                    + " = " + coverageFactor.setScale(3, RoundingMode.HALF_UP));
        } else {
            explain.add("保额 ¥" + plain(dto.coverageAmount())
                    + " ÷ 参考保额 ¥" + plain(reference)
                    + " = " + rawCoverageFactor.setScale(3, RoundingMode.HALF_UP)
                    + "，按上下限取 " + coverageFactor.setScale(2, RoundingMode.HALF_UP));
        }
        // 3.年龄系数：落在哪个区间就用哪个
        BigDecimal ageFactor = ageFactor(dto.age());
        explain.add(dto.age() + " 岁：年龄系数 " + ageFactor.setScale(2, RoundingMode.HALF_UP));

        // 4.性别系数：女性略低、男性略高
        BigDecimal genderFactor = switch (dto.gender()) {
            case "male" -> new BigDecimal("1.05");
            case "female" -> new BigDecimal("0.95");
            default -> BigDecimal.ONE;
        };
        explain.add("性别 " + dto.gender() + "：系数 " + genderFactor.setScale(2, RoundingMode.HALF_UP));

        // 5.社保系数：只有医疗险有意义，其他险种恒为 1
        BigDecimal socialSecurityFactor = BigDecimal.ONE;
        if ("medical".equals(product.getCategory())) {
            socialSecurityFactor = "no".equals(dto.socialSecurity())
                    ? new BigDecimal("1.30")
                    : BigDecimal.ONE;
            explain.add("医保: " + ("no".equals(dto.socialSecurity()) ? "无社保" : "有社保")
                    + "：系数 " + socialSecurityFactor.setScale(2, RoundingMode.HALF_UP));
        }

        // 6.合计：四舍五入到分
        BigDecimal total = base
                .multiply(coverageFactor)
                .multiply(ageFactor)
                .multiply(genderFactor)
                .multiply(socialSecurityFactor)
                .setScale(2, RoundingMode.HALF_UP);

        explain.add("合计 ¥" + plain(base)
                + " × " + coverageFactor.setScale(3, RoundingMode.HALF_UP)
                + " × " + ageFactor.setScale(2, RoundingMode.HALF_UP)
                + " × " + genderFactor.setScale(2, RoundingMode.HALF_UP)
                + " × " + socialSecurityFactor.setScale(2, RoundingMode.HALF_UP)
                + " = ¥" + plain(total));

        return new Breakdown(base, coverageFactor, ageFactor, genderFactor,
                socialSecurityFactor, total, explain);
    }

    private static BigDecimal ageFactor(int age) {
        for (int i = 0; i < AGE_UPPER.length; i++) {
            if (age <= AGE_UPPER[i]) {
                return AGE_FACTOR[i];
            }
        }
        return AGE_FACTOR[AGE_FACTOR.length - 1];
    }

    /** 金额展示：去掉多余的小数尾零 */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}