package com.insurance.biz.common.advice;

import com.insurance.biz.common.domain.R;
import com.insurance.biz.common.exception.CommonException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常出口。所有异常在这里统一转成 {@link R} 结构。
 * <p>
 * 分工：业务代码只 throw {@link CommonException} 的子类，
 * 框架层的异常（参数校验、主键冲突、Spring Security）在这里翻译。
 */
@Slf4j
@RestControllerAdvice
public class CommonExceptionAdvice {

    /** 业务异常，状态码由异常自己携带 */
    @ExceptionHandler(CommonException.class)
    public ResponseEntity<R<Void>> handleCommonException(CommonException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode())
                .body(R.error(e.getCode(), e.getMessage()));
    }

    /** 用户名或密码错误。故意不区分是用户名错还是密码错，避免被撞库探测 */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<R<Void>> handleBadCredentials(BadCredentialsException e) {
        return error(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }

    /** 账号被禁用 */
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<R<Void>> handleDisabled(DisabledException e) {
        return error(HttpStatus.FORBIDDEN, "账号已被禁用");
    }

    /** 唯一索引冲突：用户名或邮箱重复 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<R<Void>> handleDuplicateKey(DuplicateKeyException e) {
        return error(HttpStatus.CONFLICT, "用户名或邮箱已被占用");
    }

    /** @Valid 校验未通过 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("参数校验失败");
        return error(HttpStatus.BAD_REQUEST, msg);
    }

    /** @Validated 在方法参数上的校验未通过 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .findFirst()
                .orElse("参数校验失败");
        return error(HttpStatus.BAD_REQUEST, msg);
    }

    /** 请求体不是合法 JSON，或字符编码不对（例如客户端用 GBK 发的） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, "请求体格式错误，请确认是合法的 UTF-8 JSON");
    }

    /** 访问了不存在的路径。不加这个，下面的兜底会把 404 变成 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNotFound(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "接口不存在");
    }

    /** 兜底：没预料到的异常。必须记日志，否则线上只能看到 500 看不到原因 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnexpected(Exception e) {
        log.error("未处理的异常", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务器开小差了");
    }

    private ResponseEntity<R<Void>> error(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(R.error(status.value(), msg));
    }
}
