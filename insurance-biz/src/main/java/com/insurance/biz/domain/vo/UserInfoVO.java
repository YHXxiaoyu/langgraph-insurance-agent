package com.insurance.biz.domain.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** 当前登录用户的基本信息，用于 /auth/me。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UserInfoVO(Long userId, String username, String displayName) {
}