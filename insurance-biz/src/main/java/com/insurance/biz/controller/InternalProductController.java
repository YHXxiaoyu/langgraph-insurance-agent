package com.insurance.biz.controller;

import com.insurance.biz.common.domain.R;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.dto.ProductCandidateQueryDTO;
import com.insurance.biz.domain.vo.ProductVO;
import com.insurance.biz.service.IProductService;
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

import java.util.List;

/**
 * 只给 Python 侧调用的内部接口。
 * 不在 SecurityFilterChain 的 JWT 范围内，由 InternalTokenFilter 校验。
 */
@Tag(name = "内部接口", description = "仅 Python 服务调用，需要 X-Internal-Token，不接受用户 JWT")
@SecurityRequirement(name = OpenApiConfig.INTERNAL_SCHEME)
@RestController
@RequestMapping("/internal/v1/products")
@RequiredArgsConstructor
public class InternalProductController {

    private final IProductService productService;

    @Operation(summary = "候选产品查询",
            description = "每个险种各取前 N 个，按最低保费升序（空值排最后），只返回在售产品")
    @PostMapping("/candidates")
    public R<List<ProductVO>> candidates(@Valid @RequestBody ProductCandidateQueryDTO query) {
        return R.ok(productService.listCandidates(query));
    }

    @Operation(summary = "产品详情")
    @GetMapping("/{id}")
    public R<ProductVO> detail(@Parameter(description = "产品 ID") @PathVariable Long id) {
        return R.ok(productService.getDetail(id));
    }
    @Operation(summary = "在售产品全量列表",
            description = "知识库灌入脚本用：按 id 倒序返回全部在售产品（含 clauseName）")
    @GetMapping
    public R<List<ProductVO>> list() {
        return R.ok(productService.listOnSale());
    }
}
