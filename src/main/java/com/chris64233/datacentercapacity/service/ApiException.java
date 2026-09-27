package com.chris64233.datacentercapacity.service;

import org.springframework.http.HttpStatus;

/** 业务异常，携带 HTTP 状态码与稳定错误码。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + "不存在");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException stale(String what, long expected, long actual) {
        return new ApiException(HttpStatus.CONFLICT, "STALE_VERSION",
                what + "已发生变化（期望版本 " + expected + "，当前版本 " + actual + "），基于旧版本的操作被拒绝");
    }

    public static ApiException insufficient(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_CAPACITY", message);
    }
}
