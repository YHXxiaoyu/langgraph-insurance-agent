package com.insurance.biz.controller;

import com.insurance.biz.common.domain.R;
import com.insurance.biz.config.OpenApiConfig;
import com.insurance.biz.domain.dto.CreateOrderDTO;
import com.insurance.biz.domain.vo.OrderVO;
import com.insurance.biz.service.IOrderService;
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

@Tag(name = "投保订单", description = "下单 → 支付；支付成功自动发保单，只能操作自己名下的订单")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final IOrderService orderService;

    @Operation(summary = "下单", description = "从方案明细创建待支付订单；同一方案同一批明细重复下单返回同一张")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public R<OrderVO> create(@Valid @RequestBody CreateOrderDTO dto) {
        return R.ok(orderService.create(dto));
    }

    @Operation(summary = "支付", description = "订单转为已支付，同时把方案项标记已投保并发保单；重复调用幂等")
    @PostMapping("/{id}/pay")
    public R<OrderVO> pay(@Parameter(description = "订单 ID") @PathVariable String id) {
        return R.ok(orderService.pay(id));
    }
    
    @Operation(summary = "取消订单", description = "只有待支付的订单能取消；取消后释放幂等键，可以重新下单")
    @PostMapping("/{id}/cancel")
    public R<OrderVO> cancel(@Parameter(description = "订单 ID") @PathVariable String id) {
        return R.ok(orderService.cancel(id));
    }

    @Operation(summary = "我的订单", description = "按下单时间倒序，最多 20 条")
    @GetMapping
    public R<List<OrderVO>> list() {
        return R.ok(orderService.listMine());
    }

    @Operation(summary = "订单详情", description = "别人的订单返回 404，与「不存在」表现一致")
    @GetMapping("/{id}")
    public R<OrderVO> detail(@Parameter(description = "订单 ID") @PathVariable String id) {
        return R.ok(orderService.getMine(id));
    }
}