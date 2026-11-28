package com.insurance.biz;

import com.insurance.biz.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下单幂等键的单元测试。
 * <p>
 * idempotencyKey 目前是私有方法，这里用反射直接验证算法本身 ——
 * 用它而不是走完整的 service，是因为这套规则（排序、试算 id 参与计算）
 * 和数据库无关，不值得为了测它去 mock 一整套 MyBatis-Plus。
 * 如果将来把它抽成独立的工具类，这里改成普通调用即可。
 */
@DisplayName("下单幂等键")
class OrderIdempotencyTest {

    /** 只用来调静态方法，两个依赖都没有实际用途 */
    private static final OrderServiceImpl SERVICE = new OrderServiceImpl(null, null);

    private static String key(Long userId, String planId, List<String> itemIds, String quoteId) {
        return ReflectionTestUtils.invokeMethod(SERVICE, "idempotencyKey",
                userId, planId, itemIds, quoteId);
    }

    @Test
    @DisplayName("明细顺序不同，算出来的键相同")
    void 明细顺序不影响结果() {
        // 用户先选 A 再选 B、还是先选 B 再选 A，都该得到同一张订单
        assertThat(key(2L, "plan-1", List.of("a", "b"), "q1"))
                .isEqualTo(key(2L, "plan-1", List.of("b", "a"), "q1"));
    }

    @Test
    @DisplayName("换了试算结果，键不同（价格变了应该允许重新下单）")
    void 试算不同则键不同() {
        assertThat(key(2L, "plan-1", List.of("a"), "q1"))
                .isNotEqualTo(key(2L, "plan-1", List.of("a"), "q2"));
    }

    @Test
    @DisplayName("不同用户、不同方案之间互不干扰")
    void 用户与方案隔离() {
        String base = key(2L, "plan-1", List.of("a"), "q1");
        assertThat(key(3L, "plan-1", List.of("a"), "q1")).isNotEqualTo(base);
        assertThat(key(2L, "plan-2", List.of("a"), "q1")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("键长固定 32 位（MD5），能塞进 idempotency_key VARCHAR(160)")
    void 键长度固定() {
        assertThat(key(2L, "plan-1", List.of("a"), null)).hasSize(32);
    }
}
