package com.insurance.biz.common.exception;

import lombok.Getter;

/**
 * 所有业务异常的父类。
 * <p>
 * 带着一个 code（与 HTTP 状态码一致），由 CommonExceptionAdvice 统一翻译成响应。
 * 业务代码里只管 throw 子类，不用关心 HTTP 那一层。
 */
@Getter
public class CommonException extends RuntimeException {

    private final int code;

    public CommonException(String message, int code) {
        super(message);
        this.code = code;
    }

    public CommonException(String message, Throwable cause, int code) {
        super(message, cause);
        this.code = code;
    }
}
