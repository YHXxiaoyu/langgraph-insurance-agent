package com.insurance.biz.common.exception;

/** 已登录但没有权限操作，对应 HTTP 403。 */
public class ForbiddenException extends CommonException {

    public ForbiddenException(String message) {
        super(message, 403);
    }
}
