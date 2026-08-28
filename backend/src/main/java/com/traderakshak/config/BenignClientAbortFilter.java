package com.traderakshak.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/**
 * Logback TurboFilter that silences benign client-disconnect noise.
 *
 * When a browser closes an SSE live-price stream (or aborts any response mid
 * write), the OS raises a socket error while the server is still writing —
 * "Broken pipe" on Linux/macOS, "An established connection was aborted by the
 * software in your host machine" / "Connection reset" on Windows. These are
 * logged as ERROR by Tomcat's servlet valve, which sits OUTSIDE Spring's
 * {@code @ExceptionHandler} advice, so they can't be caught there.
 *
 * This filter runs before level evaluation on every logger (including Tomcat's)
 * and drops ERROR/WARN events whose throwable chain or message matches a known
 * benign client-abort phrase. Everything else passes through untouched.
 */
public class BenignClientAbortFilter extends TurboFilter {

    private static final String[] BENIGN = {
        "broken pipe",
        "connection reset by peer",
        "connection reset",
        "an established connection was aborted",
        "aborted by the software in your host machine",
        "connection was aborted",
        "clientabortexception",
        "asyncrequestnotusableexception"
    };

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        // Only inspect WARN/ERROR; leave normal logging completely untouched.
        if (level == null || !level.isGreaterOrEqual(Level.WARN)) {
            return FilterReply.NEUTRAL;
        }
        StringBuilder hay = new StringBuilder();
        if (format != null) hay.append(format.toLowerCase());
        appendThrowable(hay, t);
        if (params != null) {
            for (Object p : params) {
                if (p instanceof Throwable pt) appendThrowable(hay, pt);
            }
        }
        String text = hay.toString();
        for (String phrase : BENIGN) {
            if (text.contains(phrase)) {
                return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }

    private void appendThrowable(StringBuilder hay, Throwable t) {
        int depth = 0;
        Throwable cause = t;
        while (cause != null && depth < 10) {
            hay.append(' ').append(cause.getClass().getName().toLowerCase());
            if (cause.getMessage() != null) {
                hay.append(' ').append(cause.getMessage().toLowerCase());
            }
            cause = cause.getCause();
            depth++;
        }
    }
}
