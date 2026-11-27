package com.insurance.biz.controller;

import com.insurance.biz.common.domain.R;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.vo.PricingFormVO;
import com.insurance.biz.domain.vo.ProductPageVO;
import com.insurance.biz.service.IPremiumQuoteService;
import com.insurance.biz.service.IProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "产品", description = "保险产品查询")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
// 打开方法参数校验，@Min / @Max 才会生效（异常由 CommonExceptionAdvice 转成 400）
@Validated
public class ProductController {

    private final IProductService productService;
    private final IPremiumQuoteService quoteService;

    @Operation(summary = "在售产品列表", description = "支持按险种筛选与分页")
    @GetMapping
    public R<ProductPageVO> list(
            @Parameter(description = "险种：medical / critical_illness / life / accident，不传为全部")
            @RequestParam(required = false) String category,

            @Parameter(description = "页码，从 1 开始")
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 最小为 1")
            long page,

            @Parameter(description = "每页条数，1~50")
            @RequestParam(defaultValue = "8") @Min(value = 1, message = "pageSize 最小为 1")
            @Max(value = 50, message = "pageSize 最大为 50")
            long pageSize) {

        return R.ok(productService.pageOnSale(category, page, pageSize));
    }

    @Operation(summary = "试算表单定义",
            description = "返回这个产品试算需要填哪些项、默认值是什么；前端按 fields 渲染即可")
    @GetMapping("/{id}/pricing-form")
    public R<PricingFormVO> pricingForm(@Parameter(description = "产品 ID") @PathVariable Long id) {
        return R.ok(quoteService.pricingForm(id));
    }
}