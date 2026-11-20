package com.insurance.biz.utils;

import com.insurance.biz.security.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户的快捷入口。
 * <p>
 * 写法和 hmall 的 UserContext 一样，但底层读的是 Spring Security 的
 * SecurityContextHolder —— 它本身就是官方维护的 ThreadLocal，
 * 不需要再自己造一个。
 * <p>
 * 用法：service 里直接 {@code UserContext.getUserId()}，不用每个方法都
 * 从参数或 SecurityContext 里手工取。
 */
public final class UserContext {

    private UserContext() {
    }

    /** 当前登录用户 ID；未登录时返回 null（正常情况下被 Security 拦住，走不到这里） */
    public static Long getUserId() {
        LoginUser loginUser = getLoginUser();
        return loginUser == null ? null : loginUser.getUserId();
    }

    public static LoginUser getLoginUser() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }
}
