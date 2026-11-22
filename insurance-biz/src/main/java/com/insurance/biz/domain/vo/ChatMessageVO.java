package com.insurance.biz.domain.vo;

/**
 * 历史消息里的一条。
 * <p>
 * 只保留前端要展示的两个字段：谁说的（role）、说了什么（content）。
 * 工具调用、系统提示这些内部消息在 Python 侧就被过滤掉了，
 * 不会顺着这个接口漏到浏览器。
 */
public record ChatMessageVO(String role, String content) {
}