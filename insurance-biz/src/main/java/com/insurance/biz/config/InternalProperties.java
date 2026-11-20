package com.insurance.biz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 内部接口配置，对应 application.yml 的 internal.* 。 */
@Data
@ConfigurationProperties(prefix = "internal")
public class InternalProperties {

    /** Python 侧调用时携带的令牌，必须由环境变量或 .env 提供 */
    private String token;
}
