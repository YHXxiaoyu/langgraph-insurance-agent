package com.insurance.biz;

import com.insurance.biz.config.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId 过滤器单元测试：生成规则、透传规则、以及用完必须清理。
 * 最后一条最容易被忽略 —— MDC 是 ThreadLocal，泄漏会污染同一个 Tomcat 线程上的下一个请求。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("没有传 traceId 时自动生成，写进响应头和 MDC")
    void generatesWhenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (req, res) -> duringChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        String traceId = response.getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).isNotBlank().hasSize(16);
        assertThat(duringChain.get()).isEqualTo(traceId);
        // 请求结束后必须清理
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("上游已经带了 traceId 就沿用，不重新生成")
    void reusesIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "abc123def4567890");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("abc123def4567890");
    }
}