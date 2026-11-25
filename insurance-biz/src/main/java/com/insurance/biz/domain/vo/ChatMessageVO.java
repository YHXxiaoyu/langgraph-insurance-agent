package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 历史消息里的一条。
 * <p>
 * role / content 给气泡显示；additionalInfo 是助手回答附带的引用来源，
 * 前端按 type=clause_sources 渲染成「引用来源」列表。
 * 字段名用 snake_case，与 Python 侧和前端保持一致，所以显式标注 @JsonProperty。
 */
public record ChatMessageVO(
        String role,
        String content,
        @JsonProperty("additional_info") Object additionalInfo
) {
}