package com.insurance.biz.domain.vo;

import com.insurance.biz.domain.po.ChatThread;

import java.time.LocalDateTime;

/**
 * 会话出参。
 * <p>
 * 不含 userId —— 前端拿到的永远是自己名下的会话，再回传归属信息没有意义，
 * 反而容易被误用成参数。
 */
public record ChatThreadVO(
        String id,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ChatThreadVO from(ChatThread thread) {
        return new ChatThreadVO(
                thread.getId(),
                thread.getTitle(),
                thread.getCreatedAt(),
                thread.getUpdatedAt());
    }
}
