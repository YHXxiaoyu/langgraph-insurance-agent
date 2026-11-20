package com.insurance.biz.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 用户账号状态。
 * <p>
 * 用枚举代替裸字符串，好处是 {@code if (user.getStatus() == UserStatus.ACTIVE)}
 * 编译期就能查错，打错字不会像字符串那样静默变成 false。
 * <p>
 * 需要 application.yml 里配置
 * {@code mybatis-plus.configuration.default-enum-type-handler}，
 * 否则 @EnumValue 不生效。
 */
@Getter
@RequiredArgsConstructor
public enum UserStatus {

    /** 正常 */
    ACTIVE("active"),

    /** 已禁用，登录会被拒绝 */
    DISABLED("disabled");

    /** 存进数据库的值 */
    @EnumValue
    private final String value;
}
