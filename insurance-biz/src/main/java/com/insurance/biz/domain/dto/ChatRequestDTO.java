package com.insurance.biz.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 对话入参。
 * <p>
 * message 和 decision 二选一：
 * - 普通提问 → message
 * - 用户在「方案确认」卡片上点了按钮 → decision（{action: approve|reject, reject_reason?}）
 */
public record ChatRequestDTO(

        @NotBlank(message = "threadId 不能为空")
        @JsonProperty("thread_id")
        String threadId,

        @Size(max = 2000, message = "单条消息最长 2000 字")
        String message,

        /** 确认/拒绝方案的决策，结构由 Python 侧决定，Java 只做透传 */
        Map<String, Object> decision
) {
}