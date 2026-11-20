package com.insurance.biz.domain.vo;

/**
 * 登录 / 注册成功后返回给前端的东西。
 * <p>
 * 注意和 User 实体（po）的区别：这里只有前端需要的字段，
 * 密码摘要、创建时间这些内部字段一个都不露。
 */
public record UserLoginVO(String token, Long userId, String username) {
}
