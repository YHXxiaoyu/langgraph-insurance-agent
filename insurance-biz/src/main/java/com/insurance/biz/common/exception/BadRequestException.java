package com.insurance.biz.common.exception;

/** 请求参数不合法，对应 HTTP 400。 */
public class BadRequestException extends CommonException {

    public BadRequestException(String message) {
        super(message, 400);
    }
}
