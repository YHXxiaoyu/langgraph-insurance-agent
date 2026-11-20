package com.insurance.biz.common.exception;

/**
 * 业务规则不允许的操作，例如「余额不足」「保单已理赔」。
 * <p>
 * 和 BadRequestException 的 HTTP 状态都是 400，区别在语义：
 * BadRequest 是参数本身不对，BizIllegal 是参数没问题但当前状态不允许。
 */
public class BizIllegalException extends CommonException {

    public BizIllegalException(String message) {
        super(message, 400);
    }
}
