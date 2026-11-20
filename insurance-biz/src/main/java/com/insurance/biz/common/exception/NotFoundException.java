package com.insurance.biz.common.exception;

/** 资源不存在，对应 HTTP 404。 */
public class NotFoundException extends CommonException {

    public NotFoundException(String message) {
        super(message, 404);
    }
}
