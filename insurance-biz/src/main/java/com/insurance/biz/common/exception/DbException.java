package com.insurance.biz.common.exception;

/** 数据库操作失败，对应 HTTP 500。 */
public class DbException extends CommonException {

    public DbException(String message, Throwable cause) {
        super(message, cause, 500);
    }
}
