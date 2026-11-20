package com.insurance.biz.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.insurance.biz.domain.dto.LoginFormDTO;
import com.insurance.biz.domain.dto.RegisterFormDTO;
import com.insurance.biz.domain.po.User;
import com.insurance.biz.domain.vo.UserLoginVO;
import com.insurance.biz.enums.UserStatus;
import com.insurance.biz.mapper.UserMapper;
import com.insurance.biz.security.JwtTool;
import com.insurance.biz.security.LoginUser;
import com.insurance.biz.service.IUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTool jwtTool;

    @Override
    @Transactional
    public UserLoginVO register(RegisterFormDTO dto) {
        User user = new User();
        user.setUsername(dto.username());
        user.setEmail(dto.email());
        // 只存 BCrypt 摘要，永远不存明文
        user.setPasswordHash(passwordEncoder.encode(dto.password()));
        user.setDisplayName(StringUtils.hasText(dto.displayName())
                ? dto.displayName() : dto.username());
        user.setStatus(UserStatus.ACTIVE);
        save(user);

        log.info("新用户注册: id={}, username={}", user.getId(), user.getUsername());
        return toLoginVO(user);
    }

    @Override
    public UserLoginVO login(LoginFormDTO dto) {
        // 交给 Spring Security：查库 → 比对 BCrypt → 校验 isEnabled()
        // 用户名或密码错会抛 BadCredentialsException，由 CommonExceptionAdvice 转成 401
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.username(), dto.password()));

        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return toLoginVO(loginUser.getUser());
    }

    private UserLoginVO toLoginVO(User user) {
        return new UserLoginVO(
                jwtTool.createToken(user.getId(), user.getUsername()),
                user.getId(),
                user.getUsername());
    }
}
