package com.insurance.biz.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.insurance.biz.common.exception.CommonException;
import com.insurance.biz.config.AgentProperties;
import com.insurance.biz.config.InternalProperties;
import com.insurance.biz.config.TraceIdFilter;
import com.insurance.biz.domain.vo.ChatHistoryVO;
import com.insurance.biz.domain.vo.ClaimMaterialsVO;
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
import java.util.Map;

/**
 * Python Agent 服务的客户端。
 * <p>
 * 三类调用：流式对话（SSE 透传）、读历史（同步，要立刻回给浏览器）、
 * 删会话（异步，清 checkpoint 失败也不该影响用户）。
 * 鉴权一律用服务间的 X-Internal-Token，Python 不信用户 JWT；
 * 链路追踪一律带 X-Trace-Id，沿用 Java 入口生成的那个。
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

    /** 生成材料清单要「检索 + 调模型」，第一次必然慢；
     *  单独给它一个更宽的上限，且 Python 侧有缓存，后续调用是毫秒级 */
    private static final Duration MATERIALS_TIMEOUT = Duration.ofSeconds(45);

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
    public Flux<ServerSentEvent<String>> streamChat(String threadId, Long userId, String message, Map<String, Object> decision) {
        return webClient.post()
                .uri("/internal/v1/agent/chat")
                .header(InternalTokenFilter.HEADER, internalToken)
                // 在请求线程里取值：header 是同步构建的，进 Reactor 线程前就固定下来了
                .header(TraceIdFilter.HEADER, traceId())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new AgentChatRequest(userId, threadId, message, decision))
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
                    .header(TraceIdFilter.HEADER, traceId())
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
     * 按产品生成理赔材料清单。
     * <p>
     * 和 chatHistory 不同：失败**不抛异常**，返回 null 让调用方回落到
     * 按险种的通用清单 —— 材料提示属于辅助信息，不值得因为它让报案页打不开。
     */
    public ClaimMaterialsVO claimMaterials(Long productId) {
        try {
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/v1/agent/claim-materials")
                            .queryParam("product_id", productId)
                            .build())
                    .header(InternalTokenFilter.HEADER, internalToken)
                    .header(TraceIdFilter.HEADER, traceId())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(ClaimMaterialsVO.class)
                    .block(MATERIALS_TIMEOUT);
        } catch (Exception ex) {
            log.warn("生成理赔材料清单失败, productId={}", productId, ex);
            return null;
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
        // 在提交订阅之前把 traceId 取出来：subscribe() 之后就在别的线程上了，MDC 不跟过去
        String traceId = traceId();
        webClient.delete()
                .uri("/internal/v1/agent/threads/{id}", threadId)
                .header(InternalTokenFilter.HEADER, internalToken)
                .header(TraceIdFilter.HEADER, traceId)
                .retrieve()
                .toBodilessEntity()
                .doOnError(ex -> log.warn("清理 Agent 会话 checkpoint 失败, threadId={}", threadId, ex))
                .subscribe();
    }

    /**
     * 取当前请求的 traceId。
     * <p>
     * MDC 是线程绑定的，异步链路（比如从别的线程触发的删除）取不到，
     * 这时生成一个新的 —— 宁可断链，也不能让下游日志里冒出个空值。
     */
    private static String traceId() {
        String traceId = TraceIdFilter.currentTraceId();
        return traceId != null ? traceId : TraceIdFilter.newTraceId();
    }

    /**
     * 发给 Python 的请求体。
     * Python 那边的字段是 snake_case，所以这里显式声明 JSON 名，
     * 不去改 Java 的驼峰习惯。
     */
    private record AgentChatRequest(
            @JsonProperty("user_id") Long userId,
            @JsonProperty("thread_id") String threadId,
            String message,
            Map<String, Object> decision) {
    }
}