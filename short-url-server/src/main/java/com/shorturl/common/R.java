package com.shorturl.common;

import lombok.Data;

/**
 * 统一返回结果
 */
@Data
public class R<T> {

    /** 状态码 200=成功 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 数据 */
    private T data;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.msg = "success";
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

    public static <T> R<T> error(String msg) {
        return error(500, msg);
    }
}
