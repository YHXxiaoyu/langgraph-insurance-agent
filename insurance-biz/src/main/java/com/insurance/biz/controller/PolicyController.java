package com.insurance.biz.controller;

import com.insurance.biz.common.domain.R;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.vo.ClaimMaterialsVO;
import com.insurance.biz.domain.vo.PolicyVO;
import com.insurance.biz.service.IClaimService;
import com.insurance.biz.service.IPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "保单", description = "支付成功后自动出单；只能查自己名下的保单")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@RestController
@RequestMapping("/api/v1/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final IPolicyService policyService;
    private final IClaimService claimService;

    @Operation(summary = "我的保单列表", description = "按生效时间倒序，最多 50 条；查询前会顺手把已到期的标成 expired")
    @GetMapping
    public R<List<PolicyVO>> list() {
        return R.ok(policyService.listMine());
    }

    @Operation(summary = "保单详情", description = "别人的保单返回 404，与「不存在」表现一致")
    @GetMapping("/{id}")
    public R<PolicyVO> detail(@Parameter(description = "保单 ID") @PathVariable String id) {
        return R.ok(policyService.getMine(id));
    }

    @Operation(summary = "理赔材料清单",
            description = "依据该保单产品条款由 Agent 生成并缓存；生成失败时回落到按险种的通用清单")
    @GetMapping("/{id}/claim-materials")
    public R<ClaimMaterialsVO> claimMaterials(@Parameter(description = "保单 ID") @PathVariable String id) {
        return R.ok(claimService.claimMaterials(id));
    }
}