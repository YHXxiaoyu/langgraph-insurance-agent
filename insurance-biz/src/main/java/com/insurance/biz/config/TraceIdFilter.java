package com.insurance.biz.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级链路 ID。
 * <p>
 * 一次用户请求会穿过 Java → Python → Java 内部接口三个进程，出问题时如果只看
 * 各自的时间戳，根本分不清哪几条日志属于同一次请求。这里在请求入口生成一个
 * traceId 放进 MDC（logback 的线程上下文），并通过 X-Trace-Id 头传给下游，
 * 下游原样带回，于是 `grep <traceId>` 就能捞出整条链路。
 * <p>
 * 优先级设成最高：它必须比 Security 的过滤器更早执行，否则鉴权失败的请求
 * 就不会有 traceId，而那恰恰是最需要追踪的。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 请求/响应头名称，Python 侧必须用同一个名字 */
    public static final String HEADER = "X-Trace-Id";

    /** 日志里引用的 key，对应 logback 的 %X{traceId} */
    public static final String MDC_KEY = "traceId";

    /** traceId 长度：16 位十六进制，足够唯一，日志里也不占地方 */
    private static final int LENGTH = 16;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // 上游已经带了就用上游的（例如网关或浏览器复现问题时手工指定）
        String traceId = request.getHeader(HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = newTraceId();
        }

        MDC.put(MDC_KEY, traceId);
        // 回写给浏览器：用户报错时可以直接把这一串发过来
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat 线程是复用的，不清理会带到下一个请求上
            MDC.remove(MDC_KEY);
        }
    }

    /** 当前线程的 traceId，没有则返回 null（异步线程里通常取不到） */
    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }

    /** 生成一个新的 traceId，异步调用拿不到 MDC 时兜底用 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, LENGTH);
    }
}