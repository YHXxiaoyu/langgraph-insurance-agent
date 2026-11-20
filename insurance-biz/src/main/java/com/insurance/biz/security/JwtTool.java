package com.insurance.biz.security;

import com.insurance.biz.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 签发和解析 JWT。
 * <p>
 * 这里用 HS256（对称密钥）。hmall 用的是 RSA + jks，区别在于：
 * RSA 能让网关用公钥验签而拿不到私钥，适合多服务场景；
 * 我们只有一个服务既签发又校验，HS256 够用且少管一个密钥文件。
 */
@Component
public class JwtTool {

    private final SecretKey key;
    private final long expireMillis;

    public JwtTool(JwtProperties properties) {
        // 密钥缺失时直接启动失败，比运行到登录接口才报错好排查
        if (!StringUtils.hasText(properties.getSecret())) {
            throw new IllegalStateException(
                    "jwt.secret 未配置，请检查 .env 或环境变量 JWT_SECRET");
        }
        this.key = Keys.hmacShaKeyFor(
                properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expireMillis = properties.getExpireMinutes() * 60_000L;
    }

    /** 用户 ID 放进 subject，用户名放自定义 claim */
    public String createToken(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /**
     * 解析 token 拿 userId。
     * 签名不对 / 已过期 / 格式错误，一律返回 null，由调用方决定怎么处理。
     */
    public Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
