package com.insurance.biz;

import com.insurance.biz.config.JwtProperties;
import com.insurance.biz.security.JwtTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtTool 单元测试：不启 Spring 容器，不连数据库。
 * 覆盖签发/解析的正路，以及三种失败路径（换密钥、过期、乱码）。
 */
class JwtToolTest {

    /** HS256 要求至少 32 字节 */
    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET_32 = "fedcba9876543210fedcba9876543210";

    private static JwtTool tool(String secret, long expireMinutes) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setExpireMinutes(expireMinutes);
        return new JwtTool(properties);
    }

    @Test
    @DisplayName("签发的 token 能解析回原来的用户 ID")
    void parseBackUserId() {
        JwtTool jwtTool = tool(SECRET_32, 60);

        String token = jwtTool.createToken(42L, "alice");

        assertThat(jwtTool.parseUserId(token)).isEqualTo(42L);
    }

    @Test
    @DisplayName("用别的密钥签的 token 解析失败，返回 null")
    void wrongKeyReturnsNull() {
        String foreignToken = tool(OTHER_SECRET_32, 60).createToken(42L, "alice");

        assertThat(tool(SECRET_32, 60).parseUserId(foreignToken)).isNull();
    }

    @Test
    @DisplayName("过期 token 返回 null")
    void expiredReturnsNull() {
        // 有效期 -1 分钟：签发时过期时间就已经在过去
        JwtTool jwtTool = tool(SECRET_32, -1);
        String token = jwtTool.createToken(42L, "alice");

        assertThat(jwtTool.parseUserId(token)).isNull();
    }

    @Test
    @DisplayName("乱码 token 返回 null 而不是抛异常")
    void malformedReturnsNull() {
        assertThat(tool(SECRET_32, 60).parseUserId("not-a-jwt")).isNull();
    }

    @Test
    @DisplayName("密钥为空时构造就失败，而不是等到登录才报错")
    void blankSecretFailsFast() {
        assertThatThrownBy(() -> tool("   ", 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");
    }
}