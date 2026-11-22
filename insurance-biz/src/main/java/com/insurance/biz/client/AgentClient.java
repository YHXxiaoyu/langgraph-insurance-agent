package com.insurance.biz.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.insurance.biz.common.exception.CommonException;
import com.insurance.biz.config.AgentProperties;
import com.insurance.biz.config.InternalProperties;
import com.insurance.biz.domain.vo.ChatHistoryVO;
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

import java.time.Duration;

/**
 * Python Agent 服务的客户端。
 * <p>
 * 三类调用：流式对话（SSE 透传）、读历史（同步，要立刻回给浏览器）、
 * 删会话（异步，清 checkpoint 失败也不该影响用户）。
 * 鉴权一律用服务间的 X-Internal-Token，Python 不信用户 JWT。
 */
@Slf4j
@Component
public class AgentClient {

    /** 告诉 WebClient「把响应解码成 ServerSentEvent，data 是字符串」，
     *  用 .bodyToFlux(ServerSentEvent.class) 会丢泛型，data 变 Object */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    /** 同步调用的等待上限。读 checkpoint 很快，10 秒没回就是出问题了，
     *  不能沿用流式那个 120 秒的响应超时，否则会把 Tomcat 线程占死 */
    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(10);

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
     * 读取会话历史。同步调用：浏览器在等这个响应，必须当场给出结果。
     * <p>
     * 失败转成 503，让前端看到的是「Agent 服务不可用」而不是 500 堆栈。
     */
    public ChatHistoryVO chatHistory(String threadId) {
        try {
            return webClient.get()
                    .uri("/internal/v1/agent/threads/{id}/messages", threadId)
                    .header(InternalTokenFilter.HEADER, internalToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(ChatHistoryVO.class)
                    .block(SYNC_TIMEOUT);
        } catch (Exception ex) {
            log.error("读取 Agent 历史失败, threadId={}", threadId, ex);
            throw new CommonException("Agent 服务暂时不可用，请稍后再试", 503);
        }
    }

    /**
     * 清掉某个会话在 Python 侧的 checkpoint。
     * <p>
     * 故意不阻塞：会话主数据已经删了，这一步只是清理附属数据。
     * subscribe() 立刻返回，HTTP 删除动作在 Reactor 的 IO 线程上完成；
     * 失败只记日志 —— 留一点脏数据，好过让用户点了删除还在转圈。
     */
    public void deleteThread(String threadId) {
        webClient.delete()
                .uri("/internal/v1/agent/threads/{id}", threadId)
                .header(InternalTokenFilter.HEADER, internalToken)
                .retrieve()
                .toBodilessEntity()
                .doOnError(ex -> log.warn("清理 Agent 会话 checkpoint 失败, threadId={}", threadId, ex))
                .subscribe();
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