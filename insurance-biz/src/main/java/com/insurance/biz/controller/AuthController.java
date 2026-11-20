package com.insurance.biz.controller;

import com.insurance.biz.common.domain.R;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.dto.LoginFormDTO;
import com.insurance.biz.domain.dto.RegisterFormDTO;
import com.insurance.biz.domain.po.User;
import com.insurance.biz.domain.vo.UserInfoVO;
import com.insurance.biz.domain.vo.UserLoginVO;
import com.insurance.biz.security.LoginUser;
import com.insurance.biz.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证", description = "注册、登录、获取当前用户")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final IUserService userService;

    @Operation(summary = "注册", description = "注册成功直接返回 token，省掉一次登录请求")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public R<UserLoginVO> register(@Valid @RequestBody RegisterFormDTO dto) {
        return R.ok(userService.register(dto));
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public R<UserLoginVO> login(@Valid @RequestBody LoginFormDTO dto) {
        return R.ok(userService.login(dto));
    }

    @Operation(summary = "当前用户", description = "回显 token 解析出的用户，用于验证鉴权链路")
    @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
    @GetMapping("/me")
    public R<UserInfoVO> me(@AuthenticationPrincipal LoginUser loginUser) {
        User user = loginUser.getUser();
        return R.ok(new UserInfoVO(user.getId(), user.getUsername(), user.getDisplayName()));
    }
}
