package com.insurance.biz.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.insurance.biz.enums.UserStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户账号。对应 users 表。
 */
@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String email;

    /** 密码摘要。绝不能出现在任何 HTTP 响应里，所以加 @JsonIgnore */
    @JsonIgnore
    private String passwordHash;

    private String displayName;

    /** 账号状态，靠 MybatisEnumTypeHandler 与数据库的 active / disabled 互转 */
    private UserStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
