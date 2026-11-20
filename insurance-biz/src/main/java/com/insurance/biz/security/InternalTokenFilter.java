package com.insurance.biz.security;

import com.insurance.biz.config.InternalProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口鉴权。只处理 /internal/** 开头的请求，其他路径直接跳过。
 * <p>
 * 为什么单独一个过滤器而不是复用 JWT：
 * 调这些接口的是 Python 服务，它手上没有用户 token，只有服务间的共享密钥。
 * 两套凭证、两套过滤器，各管一段路径。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private static final String PATH_PREFIX = "/internal/";

    /** 预先编码成字节，省得每次请求都转一遍 */
    private final byte[] expected;

    public InternalTokenFilter(InternalProperties properties) {
        if (!StringUtils.hasText(properties.getToken())) {
            throw new IllegalStateException(
                    "internal.token 未配置，请检查 .env 或环境变量 INTERNAL_TOKEN");
        }
        this.expected = properties.getToken().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 关键：@Component 的 Filter 会被 Spring Boot 注册到 Servlet 容器，
     * 对「所有 URL」生效。不加这个方法，它会把 /api/v1/** 的正常请求也 401 掉。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String token = request.getHeader(HEADER);

        // 用 MessageDigest.isEqual 而不是 String.equals：
        // equals 逐字符短路比较，攻击者能靠响应时间差一位位猜出 token
        boolean valid = token != null
                && MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8));

        if (!valid) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"msg\":\"内部令牌无效\",\"data\":null}");
            return;   // 不 return 就等于没鉴权
        }

        chain.doFilter(request, response);
    }
}
