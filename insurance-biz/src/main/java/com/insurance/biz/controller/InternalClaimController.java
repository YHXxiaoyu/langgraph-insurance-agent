package com.insurance.biz.controller;

import com.insurance.biz.common.domain.R;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.vo.ClaimVO;
import com.insurance.biz.service.IClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 理赔的内部接口，只给 Python 用。
 * <p>
 * 理赔咨询 Agent 要回答"我的理赔到哪一步了"，但它没有登录态，
 * 所以由 Java 把 userId 透传进来，查询本系统的理赔记录。
 */
@Tag(name = "内部接口", description = "仅 Python 服务调用，需要 X-Internal-Token")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_SCHEME)
@RestController
@RequestMapping("/internal/v1/claims")
@RequiredArgsConstructor
public class InternalClaimController {

    private final IClaimService claimService;

    @Operation(summary = "按用户查理赔记录",
            description = "返回该用户最近 20 条，按提交时间倒序；userId 由 Java 调用方透传")
    @GetMapping
    public R<List<ClaimVO>> listByUser(@Parameter(description = "用户 ID") @RequestParam Long userId) {
        return R.ok(claimService.listByUser(userId));
    }
}