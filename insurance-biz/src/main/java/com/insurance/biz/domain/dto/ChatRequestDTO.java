package com.insurance.biz.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 对话入参。threadId 是 Java 侧已建好的会话，message 是用户这一次说的话。 */
public record ChatRequestDTO(

        @NotBlank(message = "threadId 不能为空")
        String threadId,

        @NotBlank(message = "消息内容不能为空")
        @Size(max = 2000, message = "单条消息最长 2000 字")
        String message
) {
}
