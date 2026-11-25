package com.insurance.biz.controller;

import com.insurance.biz.common.domain.R;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.dto.CreatePlanDTO;
import com.insurance.biz.domain.vo.PlanVO;
import com.insurance.biz.service.IInsurancePlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 方案落库的内部接口，只给 Python 调。
 * 走 InternalTokenFilter 的服务间令牌，不接受用户 JWT。
 */
@Tag(name = "内部接口", description = "仅 Python 服务调用，需要 X-Internal-Token")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_SCHEME)
@RestController
@RequestMapping("/internal/v1/insurance-plans")
@RequiredArgsConstructor
public class InternalPlanController {

    private final IInsurancePlanService planService;

    @Operation(summary = "方案落库",
            description = "用户确认方案后，Python 把方案与明细一起传过来，返回带 plan_id、item_id 的完整方案")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public R<PlanVO> create(@Valid @RequestBody CreatePlanDTO dto) {
        return R.ok(planService.createByAgent(dto));
    }
}