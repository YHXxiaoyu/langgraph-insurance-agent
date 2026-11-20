package com.insurance.biz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 相关配置，对应 application.yml 里的 jwt.* 。
 * <p>
 * 用 @ConfigurationProperties 代替散落的 @Value：字段集中、有类型、
 * IDEA 里能补全，改配置时不会因为 key 拼错而无感失效。
 */
@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 签名密钥，HS256 要求至少 32 字节，必须由环境变量或 .env 提供 */
    private String secret;

    /** token 有效期，单位分钟 */
    private long expireMinutes = 1440;
}
