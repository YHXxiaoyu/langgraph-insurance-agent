package com.insurance.biz.security;

import com.insurance.biz.domain.po.User;
import com.insurance.biz.enums.UserStatus;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 把业务实体 User 适配成 Spring Security 认识的 UserDetails。
 * 登录成功后，这个对象会作为 Authentication 的 principal 存在 SecurityContext 里。
 */
public class LoginUser implements UserDetails {

    @Getter
    private final User user;

    public LoginUser(User user) {
        this.user = user;
    }

    public Long getUserId() {
        return user.getId();
    }

    /** 本项目暂时不分角色，所有登录用户都是普通用户 */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    /** 返回 false 时 Spring Security 会抛 DisabledException，登录直接失败 */
    @Override
    public boolean isEnabled() {
        return user.getStatus() == UserStatus.ACTIVE;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
