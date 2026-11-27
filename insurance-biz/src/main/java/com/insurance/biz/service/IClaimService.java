package com.insurance.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.insurance.biz.domain.dto.CreateClaimDTO;
import com.insurance.biz.domain.po.Claim;
import com.insurance.biz.domain.vo.ClaimVO;

import java.util.List;

/** 理赔相关业务。 */
public interface IClaimService extends IService<Claim> {

    /** 报案：保单必须是自己的、保障中的，否则拒绝 */
    ClaimVO create(CreateClaimDTO dto);

    /** 当前用户的理赔记录，按提交时间倒序 */
    List<ClaimVO> listMine();

    /** 按用户查理赔（内部接口用，没有登录态） */
    List<ClaimVO> listByUser(Long userId);

    /** 当前用户的理赔详情，不存在或不属于自己都抛 404 */
    ClaimVO getMine(String claimId);
}