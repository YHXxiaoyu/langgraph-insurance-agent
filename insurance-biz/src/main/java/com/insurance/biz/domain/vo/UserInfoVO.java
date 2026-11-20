package com.insurance.biz.domain.vo;

/** 当前登录用户的基本信息，用于 /auth/me。 */
public record UserInfoVO(Long userId, String username, String displayName) {
}
