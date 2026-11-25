package com.insurance.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.insurance.biz.domain.dto.ProductCandidateQueryDTO;
import com.insurance.biz.domain.po.Product;
import com.insurance.biz.domain.vo.ProductPageVO;
import com.insurance.biz.domain.vo.ProductVO;

import java.util.List;

/** 保险产品相关业务。 */
public interface IProductService extends IService<Product> {

    /** 在售产品全量列表（按 id 倒序）。内部接口和知识库灌入用，不分页 */
    List<ProductVO> listOnSale();

    /** 在售产品分页列表；category 为空表示全部险种 */
    ProductPageVO pageOnSale(String category, long page, long pageSize);

    /** 每个险种各取前 N 个候选产品 */
    List<ProductVO> listCandidates(ProductCandidateQueryDTO query);

    /** 产品详情，不存在抛 NotFoundException */
    ProductVO getDetail(Long id);
}