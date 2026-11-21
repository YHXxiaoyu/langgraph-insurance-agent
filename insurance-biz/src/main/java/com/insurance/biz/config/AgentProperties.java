package com.insurance.biz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Python Agent 服务的连接配置，对应 application.yml 的 agent.* 。
 * <p>
 * 加了 @Component 就不用在别处 @EnableConfigurationProperties 里登记，
 * 组件扫描到就会按 prefix 绑定配置文件里的值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** Python 服务的根地址 */
    private String baseUrl;

    /** 等待响应头的上限，默认 120 秒 */
    private Duration responseTimeout = Duration.ofSeconds(120);

    /** 建立连接的超时，默认 5 秒 */
    private Duration connectTimeout = Duration.ofSeconds(5);
}
