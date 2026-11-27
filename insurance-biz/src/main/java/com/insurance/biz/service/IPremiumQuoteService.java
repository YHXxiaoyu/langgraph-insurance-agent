package com.insurance.biz.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.insurance.biz.domain.dto.PremiumQuoteDTO;
import com.insurance.biz.domain.po.PremiumQuote;
import com.insurance.biz.domain.vo.PremiumQuoteVO;
import com.insurance.biz.domain.vo.PricingFormVO;

/** 保费试算相关业务。 */
public interface IPremiumQuoteService extends IService<PremiumQuote> {

    /** 试算并落库，返回每一项系数和合计 */
    PremiumQuoteVO quote(PremiumQuoteDTO dto);

    /** 当前用户的某次试算结果；不存在或不属于自己都抛 404 */
    PremiumQuoteVO getMine(String quoteId);

    /** 某个产品的试算表单定义（当前由 demo_formula 模板生成） */
    PricingFormVO pricingForm(Long productId);
}