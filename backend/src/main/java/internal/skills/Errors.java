package internal.skills;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class Errors {
    private static final Logger LOG = LoggerFactory.getLogger(Errors.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Contracts.Error> api(ApiException e, HttpServletRequest r) { return error(e.status, e.code, e.getMessage(), r); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Contracts.Error> duplicate(HttpServletRequest r) { return error(409, "NAME_CONFLICT", "name 已存在或数据发生冲突", r); }
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<Contracts.Error> bad(HttpServletRequest r) { return error(400, "INVALID_ARGUMENT", "参数不合法，请检查字段、长度和版本", r); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Contracts.Error> media(HttpServletRequest r) { return error(415, "UNSUPPORTED_MEDIA_TYPE", "写请求须使用 application/json", r); }
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<Contracts.Error> missing(HttpServletRequest r) { return error(404, "NOT_FOUND", "接口不存在", r); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Contracts.Error> method(HttpServletRequest r) { return error(405, "METHOD_NOT_ALLOWED", "请求方法不支持", r); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Contracts.Error> unknown(Exception e, HttpServletRequest r) {
        // Do not print exception messages/SQL/parameters: they may include Skill content or credentials.
        LOG.error("requestId={} failureType={}", r.getAttribute("requestId"), e.getClass().getSimpleName());
        return error(500, "INTERNAL_ERROR", "服务异常，请通过 requestId 联系维护人员", r);
    }
    private ResponseEntity<Contracts.Error> error(int status, String code, String message, HttpServletRequest r) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new Contracts.Error(code, message, String.valueOf(r.getAttribute("requestId"))));
    }
}
