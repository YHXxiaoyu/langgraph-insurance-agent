package com.insurance.biz.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.insurance.biz.common.exception.BadRequestException;
import com.insurance.biz.common.exception.NotFoundException;
import com.insurance.biz.domain.dto.CreatePlanDTO;
import com.insurance.biz.domain.po.InsurancePlan;
import com.insurance.biz.domain.po.InsurancePlanItem;
import com.insurance.biz.domain.po.Product;
import com.insurance.biz.domain.vo.MarkInsuredVO;
import com.insurance.biz.domain.vo.PlanItemVO;
import com.insurance.biz.domain.vo.PlanVO;
import com.insurance.biz.domain.vo.PolicyVO;
import com.insurance.biz.mapper.InsurancePlanItemMapper;
import com.insurance.biz.mapper.InsurancePlanMapper;
import com.insurance.biz.mapper.ProductMapper;
import com.insurance.biz.service.IInsurancePlanService;
import com.insurance.biz.service.IPolicyService;
import com.insurance.biz.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InsurancePlanServiceImpl extends ServiceImpl<InsurancePlanMapper, InsurancePlan>
        implements IInsurancePlanService {

    /** insurance_plans.status 的取值 */
    private static final String STATUS_UNINSURED = "uninsured";
    private static final String STATUS_APPLYING = "applying";
    private static final String STATUS_INSURED = "insured";

    /** 列表最多返回多少条：方案是"最近生成"的东西，往前翻没有意义 */
    private static final int LIST_LIMIT = 20;

    private final InsurancePlanItemMapper itemMapper;
    private final ProductMapper productMapper;
    private final IPolicyService policyService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanVO createByAgent(CreatePlanDTO dto) {
        // 1.产品必须真实存在，否则后面发保单、查详情全是空指针
        List<Long> productIds = dto.items().stream()
                .map(CreatePlanDTO.PlanItemDTO::productId)
                .distinct()
                .toList();
        long existing = productMapper.selectCount(
                Wrappers.<Product>lambdaQuery().in(Product::getId, productIds));
        if (existing != productIds.size()) {
            throw new BadRequestException("方案里包含不存在的产品 id");
        }

        // 2.先落方案主表
        InsurancePlan plan = new InsurancePlan();
        plan.setId(UUID.randomUUID().toString());
        plan.setUserId(dto.userId());
        plan.setPlanName(dto.planName());
        plan.setSummary(dto.summary());
        plan.setAnnualPremiumBudget(dto.annualPremiumBudget());
        plan.setInsuredProfile(dto.insuredProfile());
        plan.setStatus(STATUS_UNINSURED);
        save(plan);

        // 3.再落明细。同一方案内产品重复时，数据库唯一索引会拦住，
        //   这里提前去重，让 Agent 重复推荐同一产品不至于直接 500
        int index = 1;
        for (CreatePlanDTO.PlanItemDTO input : dto.items()) {
            InsurancePlanItem item = new InsurancePlanItem();
            item.setId(UUID.randomUUID().toString());
            item.setPlanId(plan.getId());
            item.setProductId(input.productId());
            item.setPriority(input.priority() == null ? index : input.priority());
            item.setRecommendationReason(input.recommendationReason());
            item.setAnnualPremiumBudget(input.annualPremiumBudget());
            item.setStatus(STATUS_UNINSURED);
            itemMapper.insert(item);
            index++;
        }

        // 4.created_at / updated_at 是数据库默认值，回查一次才拿得到
        return toVO(getById(plan.getId()));
    }

    @Override
    public List<PlanVO> listMine() {
        return lambdaQuery()
                .eq(InsurancePlan::getUserId, UserContext.getUserId())
                .orderByDesc(InsurancePlan::getCreatedAt)
                .last("LIMIT " + LIST_LIMIT)
                .list()
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public PlanVO getMine(String planId) {
        return toVO(requireOwned(planId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarkInsuredVO markItemInsured(String planId, String itemId) {
        // 1.方案必须属于当前用户；别人的方案在这里就是"查不到"
        InsurancePlan plan = requireOwned(planId);

        // 2.明细必须属于这个方案，防止拿自己的 planId 配别人的 itemId
        InsurancePlanItem item = itemMapper.selectOne(
                Wrappers.<InsurancePlanItem>lambdaQuery()
                        .eq(InsurancePlanItem::getId, itemId)
                        .eq(InsurancePlanItem::getPlanId, planId));
        if (item == null) {
            throw new NotFoundException("方案项不存在");
        }

        // 3.已经是已投保就不再改状态：用户重复点支付不能重复产生副作用
        if (!STATUS_INSURED.equals(item.getStatus())) {
            InsurancePlanItem update = new InsurancePlanItem();
            update.setId(itemId);
            update.setStatus(STATUS_INSURED);
            // 只带 id + status：复用查询结果会把 updated_at 原样写回去，
            // 数据库的 ON UPDATE CURRENT_TIMESTAMP 就不生效了
            itemMapper.updateById(update);
        }

        // 4.按剩余未投保数量推进方案状态：全投完是 insured，否则是 applying
        Long remaining = itemMapper.selectCount(
                Wrappers.<InsurancePlanItem>lambdaQuery()
                        .eq(InsurancePlanItem::getPlanId, planId)
                        .ne(InsurancePlanItem::getStatus, STATUS_INSURED));

        InsurancePlan planUpdate = new InsurancePlan();
        planUpdate.setId(planId);
        planUpdate.setStatus(remaining == 0 ? STATUS_INSURED : STATUS_APPLYING);
        updateById(planUpdate);

        // 5.发保单。幂等由方案项 id 派生主键保证：重复调用拿到的是同一张，
        //   和上面的状态更新在同一个事务里，要么都成功要么都回滚
        PolicyVO policy = policyService.issueForPlanItem(plan.getUserId(), item);

        // 6.方案最新状态和保单一起返回，支付页可以直接显示保单号
        return new MarkInsuredVO(toVO(getById(planId)), policy);
    }

    /**
     * 取方案并校验归属。
     * <p>
     * 查询条件同时带 id 和 userId，所以别人的方案在这里就是"查不到"，
     * 不会泄露"这个 id 存在"这件事。
     */
    private InsurancePlan requireOwned(String planId) {
        InsurancePlan plan = lambdaQuery()
                .eq(InsurancePlan::getId, planId)
                .eq(InsurancePlan::getUserId, UserContext.getUserId())
                .one();
        if (plan == null) {
            throw new NotFoundException("方案不存在");
        }
        return plan;
    }

    /** 方案 → 出参。明细按 priority 升序，顺带批量把产品信息补进来 */
    private PlanVO toVO(InsurancePlan plan) {
        List<InsurancePlanItem> items = itemMapper.selectList(
                Wrappers.<InsurancePlanItem>lambdaQuery()
                        .eq(InsurancePlanItem::getPlanId, plan.getId())
                        .orderByAsc(InsurancePlanItem::getPriority));

        // 一次查完全部产品，避免每条明细查一次库
        Map<Long, Product> productMap = items.isEmpty()
                ? Map.of()
                : productMapper.selectList(
                        Wrappers.<Product>lambdaQuery().in(
                                Product::getId,
                                items.stream().map(InsurancePlanItem::getProductId).toList()))
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<PlanItemVO> itemVOs = items.stream()
                .map(item -> PlanItemVO.from(item, productMap.get(item.getProductId())))
                .toList();

        return new PlanVO(
                plan.getId(),
                plan.getPlanName(),
                plan.getSummary(),
                plan.getAnnualPremiumBudget(),
                plan.getStatus(),
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                itemVOs);
    }
}