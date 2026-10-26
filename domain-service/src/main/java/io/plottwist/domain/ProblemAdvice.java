package io.plottwist.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ProblemAdvice {
    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(ResponseStatusException error) {
        return problem(error.getStatusCode(), error.getReason() == null ? "Request failed" : error.getReason());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException error) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        var fields = new java.util.TreeMap<String, String>();
        error.getBindingResult().getFieldErrors().forEach(field -> fields.putIfAbsent(field.getField(), field.getDefaultMessage()));
        detail.setProperty("errors", fields);
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> malformed(HttpMessageNotReadableException error) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed JSON or unsupported fields");
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<ProblemDetail> database(org.springframework.dao.DataAccessException error) {
        log.error("Database operation failed: {}", error.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Data service is temporarily unavailable");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String message) {
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status.value() == 429 || status.value() == 503) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(ProblemDetail.forStatusAndDetail(status, message));
    }
}
