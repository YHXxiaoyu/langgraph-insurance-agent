package com.insurance.biz.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档的元信息。
 * <p>
 * 这里只声明「文档长什么样」和「有哪些鉴权方式」，具体每个接口的说明
 * 靠 Controller 上的 @Tag / @Operation 注解。
 * <p>
 * 打开 http://localhost:8080/doc.html 查看。
 */
@Configuration
public class OpenApiConfig {

    /** JWT 鉴权方案的 key，供 @SecurityRequirement 引用 */
    public static final String BEARER_SCHEME = "bearerAuth";

    /** 内部接口令牌方案的 key */
    public static final String INTERNAL_SCHEME = "internalToken";

    @Bean
    public OpenAPI insuranceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("安心保业务服务")
                        .version("v1")
                        .description("""
                                Java 侧业务接口。

                                - `/api/v1/**` 需要登录后拿到的 JWT（Authorization: Bearer xxx）
                                - `/internal/**` 只给 Python 服务调用，需要 X-Internal-Token
                                """)
                        .contact(new Contact().name("insurance-biz")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 token"))
                        .addSecuritySchemes(INTERNAL_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Internal-Token")
                                .description("服务间调用令牌")));
    }
}
