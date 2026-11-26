package com.insurance.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.insurance.biz.domain.dto.CreatePlanDTO;
import com.insurance.biz.domain.po.InsurancePlan;
import com.insurance.biz.domain.vo.MarkInsuredVO;
import com.insurance.biz.domain.vo.PlanVO;

import java.util.List;

/** 保险方案相关业务。 */
public interface IInsurancePlanService extends IService<InsurancePlan> {

    /** 内部接口：Agent 确认方案后落库，返回带 id 的完整方案 */
    PlanVO createByAgent(CreatePlanDTO dto);

    /** 当前用户最近的方案列表 */
    List<PlanVO> listMine();

    /** 当前用户的方案详情，不存在或不属于自己都抛 404 */
    PlanVO getMine(String planId);

    /** 把方案里的某个产品标记为已投保，同时发保单；重复调用幂等 */
    MarkInsuredVO markItemInsured(String planId, String itemId);
}