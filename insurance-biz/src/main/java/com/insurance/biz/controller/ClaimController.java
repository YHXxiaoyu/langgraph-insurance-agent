package com.insurance.biz.controller;

import com.insurance.biz.common.domain.R;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.dto.CreateClaimDTO;
import com.insurance.biz.domain.vo.ClaimVO;
import com.insurance.biz.service.IClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "理赔", description = "基于本人已生效保单提交理赔申请；审核由保险公司完成，本系统只负责受理与查询")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@RestController
@RequestMapping("/api/v1/claims")
@RequiredArgsConstructor
public class ClaimController {

    private final IClaimService claimService;

    @Operation(summary = "提交理赔申请",
            description = "保单必须属于本人且处于保障中；状态固定为 submitted")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public R<ClaimVO> create(@Valid @RequestBody CreateClaimDTO dto) {
        return R.ok(claimService.create(dto));
    }

    @Operation(summary = "我的理赔记录", description = "按提交时间倒序，最多 20 条")
    @GetMapping
    public R<List<ClaimVO>> list() {
        return R.ok(claimService.listMine());
    }

    @Operation(summary = "理赔详情", description = "别人的理赔返回 404")
    @GetMapping("/{id}")
    public R<ClaimVO> detail(@Parameter(description = "理赔 ID") @PathVariable String id) {
        return R.ok(claimService.getMine(id));
    }
}