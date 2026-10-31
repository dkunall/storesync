package com.storesync.shared.web;

import com.storesync.shared.domain.DomainConflictException;
import com.storesync.shared.domain.DomainRuleException;
import com.storesync.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.stream.Collectors;

/** Every error leaves as application/problem+json (RFC 9457) with a stable {@code code}. */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        HttpStatusCode status = HttpStatusCode.valueOf(e.status());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        ProblemDetail body = problem(status, e.code(), e.getMessage());
        e.details().forEach(body::setProperty);
        if (e.details().get("retryAfterSeconds") instanceof Number n) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, n.longValue())));
        }
        return response.body(body);
    }

    @ExceptionHandler(DomainRuleException.class)
    ResponseEntity<ProblemDetail> rule(DomainRuleException e) {
        HttpStatus status = e instanceof DomainConflictException ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(problem(status, e.code(), e.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> stale(org.springframework.dao.OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT, "version-conflict", e.getMessage()));
    }

    /** Deadlock victim or lock timeout: nothing was written, and the same request can be sent again. */
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> lockLost(org.springframework.dao.PessimisticLockingFailureException e) {
        ProblemDetail body = problem(HttpStatus.CONFLICT, "lock-conflict", "Concurrent update; retry the request");
        return ResponseEntity.status(HttpStatus.CONFLICT).header(HttpHeaders.RETRY_AFTER, "1").body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.internalServerError()
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Unexpected error"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "validation-failed", detail));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail p && p.getProperties() == null) {
            p.setProperty("code", status.is4xxClientError() ? "invalid-request" : "internal-error");
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    public static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:storesync:problem:" + code));
        HttpStatus known = HttpStatus.resolve(status.value());
        p.setTitle(known != null ? known.getReasonPhrase() : "Error");
        p.setProperty("code", code);
        return p;
    }
}
