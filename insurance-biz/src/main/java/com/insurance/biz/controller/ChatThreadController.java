package com.insurance.biz.controller;

import com.insurance.biz.client.AgentClient;
import com.insurance.biz.common.domain.R;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.dto.CreateChatThreadDTO;
import com.insurance.biz.domain.dto.RenameChatThreadDTO;
import com.insurance.biz.domain.vo.ChatHistoryVO;
import com.insurance.biz.domain.vo.ChatThreadVO;
import com.insurance.biz.service.IChatThreadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "会话", description = "对话会话的增删改查，只能操作自己名下的会话")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@RestController
@RequestMapping("/api/v1/chat-threads")
@RequiredArgsConstructor
public class ChatThreadController {

    private final IChatThreadService chatThreadService;
    private final AgentClient agentClient;

    @Operation(summary = "新建会话", description = "请求体可以省略，标题用默认值「新会话」")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public R<ChatThreadVO> create(
            @Valid @RequestBody(required = false) CreateChatThreadDTO dto) {
        return R.ok(chatThreadService.create(dto));
    }

    @Operation(summary = "会话列表", description = "按最后更新时间倒序")
    @GetMapping
    public R<List<ChatThreadVO>> list() {
        return R.ok(chatThreadService.listMine());
    }

    @Operation(summary = "历史消息",
            description = "先校验会话归属，再转调 Python 内部接口读 LangGraph checkpoint。"
                    + "历史只回放文字，不包含未确认的方案卡片")
    @GetMapping("/{id}/messages")
    public R<ChatHistoryVO> messages(@Parameter(description = "会话 ID") @PathVariable String id) {
        // 先在自己的库里确认这个会话属于当前用户，别人的会话直接 404，
        // 连 Python 都不会被调到
        chatThreadService.assertOwned(id);
        return R.ok(agentClient.chatHistory(id));
    }

    @Operation(summary = "重命名")
    @PatchMapping("/{id}")
    public R<Void> rename(@Parameter(description = "会话 ID") @PathVariable String id,
                          @Valid @RequestBody RenameChatThreadDTO dto) {
        chatThreadService.rename(id, dto);
        return R.ok();
    }

    @Operation(summary = "删除", description = "别人的会话返回 404，与「不存在」表现一致")
    @DeleteMapping("/{id}")
    public R<Void> delete(@Parameter(description = "会话 ID") @PathVariable String id) {
        chatThreadService.delete(id);
        return R.ok();
    }
}