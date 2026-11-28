package com.insurance.biz.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.insurance.biz.client.AgentClient;
import com.insurance.biz.common.exception.BadRequestException;
import com.insurance.biz.common.exception.NotFoundException;
import com.insurance.biz.domain.dto.CreateClaimDTO;
import com.insurance.biz.domain.po.Claim;
import com.insurance.biz.domain.po.Policy;
import com.insurance.biz.domain.po.Product;
import com.insurance.biz.domain.vo.ClaimMaterialsVO;
import com.insurance.biz.domain.vo.ClaimVO;
import com.insurance.biz.mapper.ClaimMapper;
import com.insurance.biz.mapper.PolicyMapper;
import com.insurance.biz.mapper.ProductMapper;
import com.insurance.biz.service.IClaimService;
import com.insurance.biz.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class ClaimServiceImpl extends ServiceImpl<ClaimMapper, Claim> implements IClaimService {

    /** claims.status 的取值 */
    private static final String STATUS_SUBMITTED = "submitted";

    /** 保单"保障中"的状态，和保单域保持一致 */
    private static final String POLICY_ACTIVE = "active";

    private static final int LIST_LIMIT = 20;

    private static final DateTimeFormatter NUMBER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Agent 不可用时的兜底清单，按险种给。
     * 和前端写死的那份保持一致 —— 宁可给通用的，也不要让用户看到空白。
     */
    private static final Map<String, List<String>> FALLBACK_MATERIALS = Map.of(
            "medical", List.of("住院病历、出院小结", "医疗费用发票原件", "费用明细清单", "身份证件、本人银行卡"),
            "critical_illness", List.of("二级以上医院诊断证明", "病理报告或影像检查报告", "住院病历", "身份证件、本人银行卡"),
            "accident", List.of("事故证明（如交通事故认定书）", "门急诊病历与检查报告", "医疗费用票据", "身份证件、本人银行卡"),
            "life", List.of("死亡证明或户籍注销证明", "受益人与被保险人关系证明", "身份证件、本人银行卡"));

    private final PolicyMapper policyMapper;
    private final ProductMapper productMapper;
    private final AgentClient agentClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClaimVO create(CreateClaimDTO dto) {
        Long userId = UserContext.getUserId();

        // 1.保单必须是自己的：查询条件带 userId，别人的保单在这里就是"查不到"
        Policy policy = policyMapper.selectOne(
                Wrappers.<Policy>lambdaQuery()
                        .eq(Policy::getId, dto.policyId())
                        .eq(Policy::getUserId, userId));
        if (policy == null) {
            throw new NotFoundException("保单不存在");
        }

        // 2.没生效或已过期的保单不能报。这一步比"保险公司审核"更早，
        //   让用户在填一大堆材料之前就知道报不了
        if (!POLICY_ACTIVE.equals(policy.getStatus())) {
            throw new BadRequestException("保单当前状态为 " + policy.getStatus() + "，暂不能申请理赔");
        }
        if (policy.getExpiresAt() != null && policy.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("保单已过保障期，不能申请理赔");
        }

        // 3.落库。状态固定为"已提交"—— 后面的审核、赔付由保险公司做，
        //   本系统没有后台审核端，所以这里不提供状态流转接口（见文档）
        String claimId = UUID.randomUUID().toString();
        Claim claim = new Claim();
        claim.setId(claimId);
        claim.setUserId(userId);
        claim.setPolicyId(policy.getId());
        claim.setProductId(policy.getProductId());
        claim.setClaimNumber(claimNumber(claimId));
        claim.setClaimType(dto.claimType());
        claim.setAmount(dto.amount());
        claim.setDescription(dto.description());
        claim.setStatus(STATUS_SUBMITTED);
        save(claim);

        // 4.submitted_at 是数据库默认值，回查一次才拿得到
        return toVO(getById(claimId), policy);
    }

    @Override
    public List<ClaimVO> listMine() {
        return toVOs(lambdaQuery()
                .eq(Claim::getUserId, UserContext.getUserId())
                .orderByDesc(Claim::getSubmittedAt)
                .last("LIMIT " + LIST_LIMIT)
                .list());
    }

    @Override
    public List<ClaimVO> listByUser(Long userId) {
        // 内部接口用：userId 由 Java 调用方透传，不能从 UserContext 取（那边没有登录态）
        return toVOs(lambdaQuery()
                .eq(Claim::getUserId, userId)
                .orderByDesc(Claim::getSubmittedAt)
                .last("LIMIT " + LIST_LIMIT)
                .list());
    }

    @Override
    public ClaimVO getMine(String claimId) {
        Claim claim = lambdaQuery()
                .eq(Claim::getId, claimId)
                .eq(Claim::getUserId, UserContext.getUserId())
                .one();
        if (claim == null) {
            throw new NotFoundException("理赔记录不存在");
        }
        Policy policy = claim.getPolicyId() == null
                ? null
                : policyMapper.selectById(claim.getPolicyId());
        Product product = claim.getProductId() == null
                ? null
                : productMapper.selectById(claim.getProductId());
        return ClaimVO.from(claim, policy, product);
    }

    @Override
    public ClaimMaterialsVO claimMaterials(String policyId) {
        // 1.保单必须是自己的，别人的保单在这里就是"查不到"
        Policy policy = policyMapper.selectOne(
                Wrappers.<Policy>lambdaQuery()
                        .eq(Policy::getId, policyId)
                        .eq(Policy::getUserId, UserContext.getUserId()));
        if (policy == null) {
            throw new NotFoundException("保单不存在");
        }

        // 2.交给 Agent 按条款生成（Python 侧有 6 小时缓存）
        ClaimMaterialsVO generated = agentClient.claimMaterials(policy.getProductId());
        if (generated != null && generated.items() != null && !generated.items().isEmpty()) {
            return generated;
        }

        // 3.生成失败就回落到按险种的通用清单：材料提示是辅助信息，
        //   不能因为模型不可用就让报案页打不开
        Product product = productMapper.selectById(policy.getProductId());
        String category = product == null ? null : product.getCategory();
        List<String> fallback = FALLBACK_MATERIALS.getOrDefault(category, List.of());
        return new ClaimMaterialsVO(
                policy.getProductId(),
                fallback,
                "按险种整理的通用清单（条款生成暂不可用）",
                false);
    }

    /** 批量转 VO：一次查完全部保单、一次查完全部产品，避免每条理赔查两次库 */
    private List<ClaimVO> toVOs(List<Claim> claims) {
        if (claims.isEmpty()) {
            return List.of();
        }
        Map<String, Policy> policyMap = policyMapper.selectList(
                        Wrappers.<Policy>lambdaQuery().in(
                                Policy::getId,
                                claims.stream().map(Claim::getPolicyId).distinct().toList()))
                .stream()
                .collect(Collectors.toMap(Policy::getId, Function.identity()));

        Map<Long, Product> productMap = productMapper.selectList(
                        Wrappers.<Product>lambdaQuery().in(
                                Product::getId,
                                claims.stream().map(Claim::getProductId).distinct().toList()))
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        return claims.stream()
                .map(claim -> ClaimVO.from(
                        claim,
                        policyMap.get(claim.getPolicyId()),
                        productMap.get(claim.getProductId())))
                .toList();
    }

    /** 单条转 VO：保单已经在手上，只补查一个产品 */
    private ClaimVO toVO(Claim claim, Policy policy) {
        Product product = claim.getProductId() == null
                ? null
                : productMapper.selectById(claim.getProductId());
        return ClaimVO.from(claim, policy, product);
    }

    /** 理赔单号：CL + 提交日期 + 主键前 6 位。主键唯一，所以单号也唯一 */
    private String claimNumber(String claimId) {
        String tail = claimId.replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        return "CL" + NUMBER_DATE.format(LocalDateTime.now()) + tail;
    }
}