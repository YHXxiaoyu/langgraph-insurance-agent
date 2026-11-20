package com.insurance.biz.common.exception;

/** 未登录或凭证无效，对应 HTTP 401。 */
public class UnauthorizedException extends CommonException {

    public UnauthorizedException(String message) {
        super(message, 401);
    }
}
