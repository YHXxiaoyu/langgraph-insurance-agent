package com.insurance.biz.controller;

import com.insurance.biz.common.domain.R;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.dto.PremiumQuoteDTO;
import com.insurance.biz.domain.vo.PremiumQuoteVO;
import com.insurance.biz.service.IPremiumQuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "保费试算", description = "按年龄、性别、保额等因素试算年保费；当前为演示费率（demo_formula）")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@RestController
@RequestMapping("/api/v1/premium-quotes")
@RequiredArgsConstructor
public class PremiumQuoteController {

    private final IPremiumQuoteService quoteService;

    @Operation(summary = "试算保费",
            description = "计算并落库，返回每一项系数和解释；结果 24 小时内有效")
    @PostMapping
    public R<PremiumQuoteVO> quote(@Valid @RequestBody PremiumQuoteDTO dto) {
        return R.ok(quoteService.quote(dto));
    }

    @Operation(summary = "查询试算结果", description = "别人的试算记录返回 404")
    @GetMapping("/{id}")
    public R<PremiumQuoteVO> detail(@Parameter(description = "试算 ID") @PathVariable String id) {
        return R.ok(quoteService.getMine(id));
    }
}