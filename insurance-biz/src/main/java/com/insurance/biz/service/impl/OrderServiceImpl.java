package com.insurance.biz.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.insurance.biz.common.exception.BadRequestException;
import com.insurance.biz.common.exception.NotFoundException;
import com.insurance.biz.domain.dto.CreateOrderDTO;
import com.insurance.biz.domain.po.Order;
import com.insurance.biz.domain.po.OrderItem;
import com.insurance.biz.domain.vo.OrderVO;
import com.insurance.biz.domain.vo.PlanItemVO;
import com.insurance.biz.domain.vo.PlanVO;
import com.insurance.biz.mapper.OrderMapper;
import com.insurance.biz.service.IInsurancePlanService;
import com.insurance.biz.service.IOrderService;
import com.insurance.biz.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    /** orders.status 的取值 */
    private static final String STATUS_PENDING_PAYMENT = "pending_payment";
    private static final String STATUS_PAID = "paid";
    private static final String STATUS_PENDING_POLICY = "pending_policy";
    private static final String STATUS_COMPLETED = "completed";

    /** insurance_plan_items.status 里"已投保"的值，和方案域保持一致 */
    private static final String ITEM_INSURED = "insured";

    private static final int LIST_LIMIT = 20;

    /** 订单号里的时间段，精确到秒 */
    private static final DateTimeFormatter NUMBER_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final IInsurancePlanService planService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO create(CreateOrderDTO dto) {
        Long userId = UserContext.getUserId();

        // 1.方案必须是自己的。getMine 内部带 userId 条件，别人的方案在这一步就是 404
        PlanVO plan = planService.getMine(dto.planId());

        // 2.把方案明细做成"按 id 一查即得"，下面校验和取价都靠它
        Map<String, PlanItemVO> itemById = plan.items().stream()
                .collect(Collectors.toMap(PlanItemVO::id, Function.identity()));

        List<String> itemIds = dto.itemIds().stream().distinct().toList();

        List<OrderItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (String itemId : itemIds) {
            PlanItemVO item = itemById.get(itemId);
            if (item == null) {
                throw new BadRequestException("方案项不属于该方案：" + itemId);
            }
            if (ITEM_INSURED.equals(item.status())) {
                throw new BadRequestException("该产品已投保，无需重复下单");
            }

            BigDecimal premium = item.annualPremiumBudget() == null
                    ? BigDecimal.ZERO
                    : item.annualPremiumBudget();
            items.add(new OrderItem(
                    plan.id(),
                    item.id(),
                    item.productId(),
                    item.productName(),
                    premium,
                    item.priority()));
            total = total.add(premium);
        }

        // 3.幂等：同一用户 + 同一方案 + 同一批明细，算出来的键是一样的。
        //   已经有了就直接返回，重复点击不会产生第二张订单
        String key = idempotencyKey(userId, plan.id(), itemIds);
        Order existing = lambdaQuery().eq(Order::getIdempotencyKey, key).one();
        if (existing != null) {
            return toVO(existing);
        }

        // 4.组装订单
        String orderId = UUID.randomUUID().toString();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setThreadId(dto.threadId());
        order.setOrderNumber(orderNumber(orderId));
        order.setItems(items);
        order.setTotalPremium(total);
        order.setStatus(STATUS_PENDING_PAYMENT);
        order.setRecommendationSnapshot(Map.of(
                "plan_id", plan.id(),
                "plan_name", String.valueOf(plan.planName()),
                "summary", String.valueOf(plan.summary())));
        order.setIdempotencyKey(key);
        order.setIdempotencyFingerprint(key);

        try {
            save(order);
        } catch (DuplicateKeyException e) {
            // 并发：两个人同时点了下单，另一个先插进去了，返回它的那单
            return toVO(lambdaQuery().eq(Order::getIdempotencyKey, key).one());
        }

        // 5.created_at / updated_at 是数据库默认值，回查一次才拿得到
        return toVO(getById(orderId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO pay(String orderId) {
        // 1.订单必须是自己的
        Order order = requireOwned(orderId);

        // 2.幂等：已经付过就直接返回，不会重复发保单
        String status = order.getStatus();
        if (STATUS_PAID.equals(status)
                || STATUS_COMPLETED.equals(status)
                || STATUS_PENDING_POLICY.equals(status)) {
            return toVO(order);
        }
        if (!STATUS_PENDING_PAYMENT.equals(status)) {
            throw new BadRequestException("订单当前状态为 " + status + "，不能支付");
        }

        // TODO(支付): 这里直接认为"钱到账了"。接入真实支付渠道后，
        //  应该校验支付回调签名 / 查询支付流水，成功才继续往下走。
        // 3.逐项标记已投保 + 发保单。markItemInsured 自己带事务，
        //   这里会加入外层事务，任何一项失败整单回滚
        for (OrderItem item : order.getItems()) {
            planService.markItemInsured(item.getPlanId(), item.getPlanItemId());
        }

        // 4.把订单推进到已支付。只带 id + status，让 ON UPDATE 生效
        Order update = new Order();
        update.setId(orderId);
        update.setStatus(STATUS_PAID);
        updateById(update);

        return toVO(getById(orderId));
    }

    @Override
    public List<OrderVO> listMine() {
        return lambdaQuery()
                .eq(Order::getUserId, UserContext.getUserId())
                .orderByDesc(Order::getCreatedAt)
                .last("LIMIT " + LIST_LIMIT)
                .list()
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public OrderVO getMine(String orderId) {
        return toVO(requireOwned(orderId));
    }

    /** 取订单并校验归属：别人的订单在 SQL 层就查不到 */
    private Order requireOwned(String orderId) {
        Order order = lambdaQuery()
                .eq(Order::getId, orderId)
                .eq(Order::getUserId, UserContext.getUserId())
                .one();
        if (order == null) {
            throw new NotFoundException("订单不存在");
        }
        return order;
    }

    /**
     * 幂等键 = SHA-256(用户 id | 方案 id | 排序后的明细 id)。
     * <p>
     * 明细先排序再拼，是为了让"先选 A 再选 B"和"先选 B 再选 A"得到同一个键 ——
     * 否则用户换个顺序点，就会多出一张订单。
     */
    private String idempotencyKey(Long userId, String planId, List<String> itemIds) {
        String raw = userId + "|" + planId + "|" + itemIds.stream().sorted()
                .collect(Collectors.joining(","));
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 订单号：OD + 下单时间 + 主键前 6 位。主键唯一，所以订单号也唯一 */
    private String orderNumber(String orderId) {
        String tail = orderId.replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        return "OD" + NUMBER_TIME.format(LocalDateTime.now()) + tail;
    }

    /** 下单时的商品名快照已经存在 items 里，这里只做形状转换 */
    private OrderVO toVO(Order order) {
        return new OrderVO(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getTotalPremium(),
                order.getItems(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}