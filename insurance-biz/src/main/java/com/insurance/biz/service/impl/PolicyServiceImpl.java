package com.insurance.biz.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.insurance.biz.common.exception.NotFoundException;
import com.insurance.biz.domain.po.InsurancePlanItem;
import com.insurance.biz.domain.po.Policy;
import com.insurance.biz.domain.po.Product;
import com.insurance.biz.domain.vo.PolicyVO;
import com.insurance.biz.mapper.PolicyMapper;
import com.insurance.biz.mapper.ProductMapper;
import com.insurance.biz.service.IPolicyService;
import com.insurance.biz.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PolicyServiceImpl extends ServiceImpl<PolicyMapper, Policy>
        implements IPolicyService {

    /** policies.status 的取值 */
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_EXPIRED = "expired";

    /** 列表最多返回多少条：保单是逐年累积的，50 条够看，也不至于一次拉回几千条 */
    private static final int LIST_LIMIT = 50;

    /** 保单号里的日期段 */
    private static final DateTimeFormatter NUMBER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ProductMapper productMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PolicyVO issueForPlanItem(Long userId, InsurancePlanItem item) {
        // 1.主键由方案项 id 算出来，所以"这张保单在不在"直接用 getById 判断
        String policyId = policyIdOf(item.getId());
        Policy existing = getById(policyId);
        if (existing != null) {
            // 已经发过：重复支付、网络重试、用户连点都会走这里
            return toVO(existing);
        }

        // 2.新发一张
        LocalDateTime effectiveAt = LocalDateTime.now().withNano(0);
        Policy policy = new Policy();
        policy.setId(policyId);
        policy.setUserId(userId);
        policy.setProductId(item.getProductId());
        policy.setPolicyNumber(policyNumber(policyId, effectiveAt));
        policy.setStatus(STATUS_ACTIVE);
        policy.setEffectiveAt(effectiveAt);
        // TODO(费率/条款): products 表还没有保障期限字段，先统一按 1 年期。
        //  接入费率表/条款解析后，改成按产品配置计算
        policy.setExpiresAt(effectiveAt.plusYears(1));

        try {
            save(policy);
        } catch (DuplicateKeyException e) {
            // 3.并发场景：两个请求同时通过第 1 步检查，另一个先插入成功。
            //   主键唯一索引会拦住这一次，把已有记录读出来返回即可
            return toVO(getById(policyId));
        }

        // 4.created_at / updated_at 是数据库默认值，回查一次才拿得到
        return toVO(getById(policyId));
    }

    @Override
    public List<PolicyVO> listMine() {
        Long userId = UserContext.getUserId();

        // 1.懒更新到期状态：已经过了失效时间但还标着 active 的，顺手改成 expired。
        //   项目里没有定时任务，放在查询前做，用户一打开「我的保单」看到的就是准确状态。
        //   只更新本人数据，不会扫全表
        lambdaUpdate()
                .eq(Policy::getUserId, userId)
                .eq(Policy::getStatus, STATUS_ACTIVE)
                .lt(Policy::getExpiresAt, LocalDateTime.now())
                .set(Policy::getStatus, STATUS_EXPIRED)
                .update();

        // 2.按生效时间倒序：最近买的排在最前面
        List<Policy> policies = lambdaQuery()
                .eq(Policy::getUserId, userId)
                .orderByDesc(Policy::getEffectiveAt)
                .last("LIMIT " + LIST_LIMIT)
                .list();

        return toVOs(policies);
    }

    @Override
    public PolicyVO getMine(String policyId) {
        // 查询条件同时带 id 和 userId：别人的保单在这里就是"查不到"，
        // 不会泄露"这个 id 存在"这件事
        Policy policy = lambdaQuery()
                .eq(Policy::getId, policyId)
                .eq(Policy::getUserId, UserContext.getUserId())
                .one();
        if (policy == null) {
            throw new NotFoundException("保单不存在");
        }
        return toVO(policy);
    }

    /**
     * 由方案项 id 派生保单主键。
     * <p>
     * 用的是 name-based UUID（同一个输入永远得到同一个 UUID），
     * 而不是 randomUUID —— 幂等就靠这一点。加 "policy:" 前缀是为了
     * 和直接用 itemId 生成的主键区分开，避免将来撞上。
     */
    private String policyIdOf(String itemId) {
        return UUID.nameUUIDFromBytes(("policy:" + itemId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /**
     * 生成给用户看的保单号，格式：AH + 生效日期 + 主键前 10 位。
     * 例：AH20261126A1B2C3D4E5。主键本身唯一，所以这个号也唯一。
     */
    private String policyNumber(String policyId, LocalDateTime effectiveAt) {
        String compact = policyId.replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        return "AH" + NUMBER_DATE.format(effectiveAt) + compact;
    }

    /** 单条转 VO：查一次产品表 */
    private PolicyVO toVO(Policy policy) {
        Product product = policy.getProductId() == null
                ? null
                : productMapper.selectById(policy.getProductId());
        return PolicyVO.from(policy, product);
    }

    /** 列表转 VO：一次查完全部产品，避免每条保单查一次库 */
    private List<PolicyVO> toVOs(List<Policy> policies) {
        if (policies.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> productMap = productMapper.selectList(
                        Wrappers.<Product>lambdaQuery().in(
                                Product::getId,
                                policies.stream().map(Policy::getProductId).distinct().toList()))
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return policies.stream()
                .map(policy -> PolicyVO.from(policy, productMap.get(policy.getProductId())))
                .toList();
    }
}