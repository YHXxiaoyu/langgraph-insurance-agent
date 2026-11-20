package com.insurance.biz.domain.dto;

import jakarta.validation.constraints.Size;

/** 新建会话入参。title 可以不传，不传就用默认名。 */
public record CreateChatThreadDTO(
        @Size(max = 200, message = "标题最长 200 字")
        String title
) {
}
