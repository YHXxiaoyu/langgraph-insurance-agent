package com.insurance.biz.controller;

import com.insurance.biz.client.AgentClient;
import com.insurance.biz.common.exception.BadRequestException;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.dto.ChatRequestDTO;
import com.insurance.biz.service.IChatThreadService;
import com.insurance.biz.utils.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 对话入口。流程：校验会话归属 → 转发给 Python → 把 SSE 原样透传回浏览器。
 * <p>
 * Java 在这里承担两件事：一是确认这个会话确实是当前用户的，
 * 二是让前端只需要认识一个后端地址，不用再直连 Python。
 */
@Tag(name = "对话", description = "SSE 流式对话，Java 校验会话归属后透传给 Python Agent")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final IChatThreadService chatThreadService;
    private final AgentClient agentClient;

    @Operation(summary = "流式对话",
            description = "返回 text/event-stream。event=message 是增量文本，"
                    + "event=done 表示结束，event=error 表示 Agent 服务异常")
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody ChatRequestDTO dto) {

        // 普通提问和"确认方案"是两条路径，至少得有一个
        if (!StringUtils.hasText(dto.message()) && dto.decision() == null) {
            throw new BadRequestException("message 和 decision 不能同时为空");
        }

        Long userId = UserContext.getUserId();
        chatThreadService.assertOwned(dto.threadId());

        return agentClient.streamChat(
                dto.threadId(), userId, dto.message(), dto.decision());
    }
}
