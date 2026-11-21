package com.insurance.biz.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.insurance.biz.config.AgentProperties;
import com.insurance.biz.config.InternalProperties;
import com.insurance.biz.security.InternalTokenFilter;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

/**
 * Python Agent 服务的客户端。
 * <p>
 * 只做一件事：把 Java 这边的对话请求转发给 Python，再把 SSE 流转回来。
 * 鉴权用服务间的 X-Internal-Token，不是用户 JWT —— Python 不信用户 token。
 */
@Slf4j
@Component
public class AgentClient {

    /** 告诉 WebClient「把响应解码成 ServerSentEvent，data 是字符串」，
     *  用 .bodyToFlux(ServerSentEvent.class) 会丢泛型，data 变 Object */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final String internalToken;

    public AgentClient(WebClient.Builder builder,
                       AgentProperties properties,
                       InternalProperties internalProperties) {

        // 超时设置必须在底层 HttpClient 上做，WebClient.Builder 不直接提供
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getResponseTimeout());

        // WebClient.Builder 是 prototype 作用域的，改它不会影响别处
        this.webClient = builder
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();

        this.internalToken = internalProperties.getToken();
    }

    /**
     * 流式对话。返回的 Flux 是懒的 —— 不订阅就不会真正发请求，
     * 所以调用方要保证自己处在能处理异常的位置。
     */
    public Flux<ServerSentEvent<String>> streamChat(String threadId, Long userId, String message) {
        return webClient.post()
                .uri("/internal/v1/agent/chat")
                .header(InternalTokenFilter.HEADER, internalToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new AgentChatRequest(userId, threadId, message))
                .retrieve()
                .bodyToFlux(SSE_TYPE)
                .onErrorResume(ex -> {
                    // 异常是订阅时才炸的，不接住的话前端只会看到连接被掐断
                    log.error("调用 Agent 服务失败, threadId={}", threadId, ex);
                    return Flux.just(ServerSentEvent.<String>builder("Agent 服务暂时不可用，请稍后再试")
                            .event("error")
                            .build());
                });
    }

    /**
     * 发给 Python 的请求体。
     * Python 那边的字段是 snake_case，所以这里显式声明 JSON 名，
     * 不去改 Java 的驼峰习惯。
     */
    private record AgentChatRequest(
            @JsonProperty("user_id") Long userId,
            @JsonProperty("thread_id") String threadId,
            String message) {
    }
}
