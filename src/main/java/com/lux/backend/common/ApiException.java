package com.lux.backend.common;

/** 可直接映射为 HTTP 响应的业务异常，同时携带状态码、稳定错误代码和说明。 */
public class ApiException extends RuntimeException {
    /** HTTP 状态码，例如 400、404 或 409。 */
    private final int status;
    /** 稳定业务错误代码，供客户端判断错误类别。 */
    private final String code;

    /** 同时保存 HTTP 状态、业务代码和错误说明，交给统一异常处理器输出。 */
    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 返回用于构造 HTTP 错误响应的状态码。 */
    public int status() { return status; }
    /** 返回稳定的业务错误代码，供客户端按错误类别处理。 */
    public String code() { return code; }

    /** 创建统一的资源不存在错误，不区分缺失和归属不匹配。 */
    public static ApiException notFound() {
        return new ApiException(404, "RESOURCE_NOT_FOUND", "Resource not found");
    }

    /** 创建当前状态或幂等输入冲突的 HTTP 409 错误。 */
    public static ApiException conflict(String message) {
        return new ApiException(409, "STATE_CONFLICT", message);
    }

    /** 创建请求参数或内容不合法的 HTTP 400 错误。 */
    public static ApiException badRequest(String message) {
        return new ApiException(400, "INVALID_REQUEST", message);
    }
}
