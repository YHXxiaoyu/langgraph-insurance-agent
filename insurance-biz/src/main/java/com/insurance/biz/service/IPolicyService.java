package com.insurance.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.insurance.biz.domain.po.InsurancePlanItem;
import com.insurance.biz.domain.po.Policy;
import com.insurance.biz.domain.vo.PolicyVO;

import java.util.List;

/** 保单相关业务。 */
public interface IPolicyService extends IService<Policy> {

    /**
     * 为方案项发一张保单。
     * <p>
     * 幂等：同一个方案项重复调用只会返回同一张保单，不会产生第二条记录。
     * 由方案域在「标记已投保」时调用，不在 Controller 里直接暴露。
     *
     * @param userId 投保人，来自方案归属（内部调用时没有 SecurityContext）
     * @param item   已确认的方案明细
     */
    PolicyVO issueForPlanItem(Long userId, InsurancePlanItem item);

    /** 当前用户的保单列表，按生效时间倒序 */
    List<PolicyVO> listMine();

    /** 当前用户的保单详情，不存在或不属于自己都抛 404 */
    PolicyVO getMine(String policyId);
}