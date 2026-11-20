package com.insurance.biz.common.domain;

import lombok.Data;

/**
 * 统一返回体。所有对外接口都返回这个壳，前端只解析一套结构。
 *
 * <pre>
 * {"code": 200, "msg": "OK", "data": ...}
 * </pre>
 *
 * @param <T> 业务数据
 */
@Data
public class R<T> {

    /** 业务状态码，与 HTTP 状态码保持一致，方便排查 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 业务数据 */
    private T data;

    private R() {
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.msg = "OK";
        r.data = data;
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> error(int code, String msg) {
        R<T> r = new R<>();
        r.code = code;
        r.msg = msg;
        return r;
    }
}
