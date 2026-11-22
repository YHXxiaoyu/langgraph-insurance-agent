package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * 会话历史出参。字段名用 snake_case，与前端 ChatHistory 类型对齐，
 * 也延续改造前 Python 接口的格式，前端不用改解析逻辑。
 * <p>
 * interrupt / additionalInfo 是方案确认卡片用的，阶段 6 恒为 null：
 * 历史接口只回放文字，不重放中断状态，刷新后未确认的卡片不会恢复。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatHistoryVO(
        String threadId,
        List<ChatMessageVO> messages,
        Object interrupt,
        Object additionalInfo
) {
}