package com.traderakshak.exception;

import com.traderakshak.config.AppProperties;
import com.traderakshak.util.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

// R15: Exact error codes exposed (never swallowed)
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final AppProperties props;

    @ExceptionHandler(TradeRakshakException.class)
    public ResponseEntity<Map<String, Object>> handleAppException(TradeRakshakException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return error(HttpStatus.UNAUTHORIZED, "AUTH-001", "Incorrect email or password.");
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<Map<String, Object>> handleLocked(LockedException ex) {
        return error(HttpStatus.LOCKED, "AUTH-003", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return error(HttpStatus.FORBIDDEN, "AUTH-403", "Access denied. Insufficient permissions.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = baseError("VAL-001", "Validation failed");
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "UPL-001",
            "File too large. Maximum size is " + props.getStorage().toString());
    }

    /**
     * Database row-lock contention (PostgreSQL SQLState 55P03 -> Spring's
     * CannotAcquireLockException / PessimisticLockingFailureException). Returned
     * as HTTP 409 with a friendly retry message instead of a 500, so the client
     * can back off and retry rather than surfacing a server error.
     */
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleLockContention(Exception ex) {
        log.warn("Database lock contention: {}", ex.getMessage());
        return error(HttpStatus.CONFLICT, "DB-409",
            "This record is currently being updated. Please try again in a moment.");
    }

    /**
     * Client disconnected (e.g. browser closed an SSE live-price stream) before
     * the response finished writing. The socket is gone, so there is nothing to
     * send back; log at debug instead of a full ERROR stack trace.
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(Exception ex) {
        log.debug("Client disconnected before response completed: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        // Broken-pipe / aborted-connection variants are benign client disconnects
        // (e.g. browser closed an SSE stream). Log at debug, never 500.
        if (isBenignClientAbort(ex)) {
            log.debug("Client aborted request: {}", ex.getMessage());
            return null;
        }
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "SYS-500",
            UserMessage.of(ex, "Something went wrong and the action was stopped. Nothing was half-saved — please try again, and report it if it keeps happening."));
    }

    /**
     * Turns the common failures into something a non-technical user can act on.
     *
     * <p>Everything used to return "Something went wrong. Please contact support." — which hid,
     * for example, a backup that had actually written its file and then failed on one bad
     * column name. The technical detail still goes to the log; the screen gets the plain
     * version plus what to do about it.
     */
    private String plainLanguageMessage(Throwable ex) {
        String detail = "";
        for (Throwable c = ex; c != null && detail.isEmpty(); c = c.getCause()) {
            if (c.getMessage() != null) detail = c.getMessage();
            if (c.getCause() == c) break;
        }
        String lower = detail.toLowerCase();

        if (lower.contains("out of memory") || ex instanceof OutOfMemoryError) {
            return "The server ran out of memory handling this request. It is usually caused by "
                 + "working on too much data at once — try a smaller date range or fewer symbols.";
        }
        if (lower.contains("statement timeout") || lower.contains("canceling statement")) {
            return "This took too long and was stopped so the app stays responsive. "
                 + "It usually means the data set is very large or another job is running — "
                 + "wait for any running job to finish and try again.";
        }
        if (lower.contains("lock timeout") || lower.contains("could not obtain lock")) {
            return "Another task is using the same records right now. Wait a few seconds and try again.";
        }
        if (lower.contains("does not exist") && lower.contains("column")) {
            return "The app tried to save into a field that is missing from the database. "
                 + "This needs a fix in the app — please report it with the time it happened.";
        }
        if (lower.contains("does not exist") && lower.contains("relation")) {
            return "A required table is missing from the database. "
                 + "This needs a fix in the app — please report it with the time it happened.";
        }
        if (lower.contains("connection") && (lower.contains("refused") || lower.contains("closed"))) {
            return "The app could not reach the database. Check that the database is running, then try again.";
        }
        if (lower.contains("duplicate key")) {
            return "That record already exists, so it was not added again.";
        }
        if (lower.contains("no space left") || lower.contains("disk full")) {
            return "The disk is full. Free some space — old backup files in the backups folder are a good place to start.";
        }
        if (lower.contains("read timed out") || lower.contains("connect timed out")) {
            return "The broker did not respond in time. This is usually temporary — please try again in a moment.";
        }
        return "Something went wrong and the action was stopped. Nothing was half-saved. "
             + "Please try again, and report it if it keeps happening.";
    }

    private boolean isBenignClientAbort(Throwable ex) {
        int depth = 0;
        for (Throwable c = ex; c != null && depth < 10; c = c.getCause(), depth++) {
            String cn = c.getClass().getName().toLowerCase();
            if (cn.contains("clientabortexception") || cn.contains("asyncrequestnotusable")) return true;
            String msg = c.getMessage() == null ? "" : c.getMessage().toLowerCase();
            // "Cannot start async: [ERROR]" is the same event seen one step later. The SSE
            // live-price stream failed, Tomcat re-dispatched the request as an ERROR, and the
            // handler mapping sent it back to a method that returns an SseEmitter - which tries
            // to start async on a request that is already finished. Treating it as a real fault
            // logged a full stack trace and then failed again inside the handler itself: the
            // response Content-Type is pinned to text/event-stream by `produces`, so the JSON
            // error body has no converter and throws HttpMessageNotWritableException on top.
            // Returning no body at all is both correct and quiet - the socket is already gone.
            if (msg.contains("cannot start async")
                    || msg.contains("broken pipe")
                    || msg.contains("connection reset")
                    || msg.contains("aborted by the software in your host machine")
                    || msg.contains("an established connection was aborted")
                    || msg.contains("connection was aborted")) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String msg) {
        return ResponseEntity.status(status).body(baseError(code, msg));
    }

    private Map<String, Object> baseError(String code, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.put("disclaimer", props.getSebiDisclaimer());
        return body;
    }
}
