package com.insurance.biz.controller;

import com.insurance.biz.common.domain.R;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.vo.PlanVO;
import com.insurance.biz.service.IInsurancePlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "保险方案", description = "Agent 生成的方案，确认后落库；只能操作自己名下的方案")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@RestController
@RequestMapping("/api/v1/insurance-plans")
@RequiredArgsConstructor
public class InsurancePlanController {

    private final IInsurancePlanService planService;

    @Operation(summary = "我的方案列表", description = "按创建时间倒序，最多 20 条")
    @GetMapping
    public R<List<PlanVO>> list() {
        return R.ok(planService.listMine());
    }

    @Operation(summary = "方案详情", description = "别人的方案返回 404，与「不存在」表现一致")
    @GetMapping("/{id}")
    public R<PlanVO> detail(@Parameter(description = "方案 ID") @PathVariable String id) {
        return R.ok(planService.getMine(id));
    }

    @Operation(summary = "标记方案项已投保",
            description = "投保流程支付成功后调用。重复调用幂等，不重复产生副作用")
    @PatchMapping("/{id}/items/{itemId}/insured")
    public R<PlanVO> markItemInsured(
            @Parameter(description = "方案 ID") @PathVariable String id,
            @Parameter(description = "方案项 ID") @PathVariable String itemId) {
        return R.ok(planService.markItemInsured(id, itemId));
    }
}