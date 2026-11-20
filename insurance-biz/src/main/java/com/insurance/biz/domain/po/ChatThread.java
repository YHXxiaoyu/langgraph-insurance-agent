package com.insurance.biz.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话会话。对应 chat_threads 表。
 * <p>
 * 主键是 CHAR(36) 的 UUID 字符串而不是自增数字，因为同一个 id 还要
 * 作为 Python 侧 LangGraph 的 thread_id 用，两边必须一致。
 */
@Data
@TableName("chat_threads")
public class ChatThread {

    /** UUID 字符串，由 Java 侧生成（IdType.INPUT 表示不自增、不自动生成） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属用户。所有查询都必须带上这个条件，否则就是越权 */
    private Long userId;

    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
