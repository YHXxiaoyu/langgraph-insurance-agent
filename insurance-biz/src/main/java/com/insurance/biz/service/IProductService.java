package com.insurance.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.insurance.biz.domain.dto.ProductCandidateQueryDTO;
import com.insurance.biz.domain.po.Product;
import com.insurance.biz.domain.vo.ProductVO;

import java.util.List;

/** 保险产品相关业务。 */
public interface IProductService extends IService<Product> {

    /** 在售产品列表（按 id 倒序） */
    List<ProductVO> listOnSale();
    /** 每个险种各取前 N 个候选产品 */
    List<ProductVO> listCandidates(ProductCandidateQueryDTO query);

    /** 产品详情，不存在抛 NotFoundException */
    ProductVO getDetail(Long id);

}
