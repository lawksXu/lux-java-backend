package com.lux.backend.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 全局 HTTP 异常转换器，将业务错误和常见框架异常转换为带业务 code 的 ProblemDetail。 */
@RestControllerAdvice
public class ApiErrors {
    /** 把业务异常转换为 ProblemDetail，并增加客户端可判断的 code 字段。 */
    @ExceptionHandler(ApiException.class)
    public ProblemDetail application(ApiException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(exception.status()), exception.getMessage());
        problem.setProperty("code", exception.code());
        return problem;
    }

    /** 将请求绑定、参数校验和解析错误统一转换为 HTTP 400。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, IllegalArgumentException.class})
    public ProblemDetail invalid(Exception exception) {
        return application(ApiException.badRequest("Invalid request parameters or content"));
    }

    /** 将数据库唯一键等完整性冲突转换为 HTTP 409。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail constraint(DataIntegrityViolationException exception) {
        return application(ApiException.conflict("Resource or operation conflicts with an existing record"));
    }

    /** 将 Servlet 上传大小限制异常转换为 HTTP 413。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail tooLarge(MaxUploadSizeExceededException exception) {
        return application(new ApiException(413, "ASSET_TOO_LARGE", "Upload exceeds the configured limit"));
    }

    /** 把最终未解决的数据库锁冲突转换为 409，提示客户端重新读取状态后再试。 */
    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class, jakarta.persistence.PessimisticLockException.class,
            jakarta.persistence.LockTimeoutException.class})
    public ProblemDetail concurrent(Exception exception) {
        return application(new ApiException(409, "CONCURRENT_MODIFICATION",
                "Resource changed concurrently; reload the current state and retry"));
    }
}
