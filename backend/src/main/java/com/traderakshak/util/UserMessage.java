package com.traderakshak.util;

/**
 * Turns technical failures into wording a non-technical user can act on.
 *
 * <p>Controllers used to append {@code e.getMessage()} straight onto user-facing text, so the
 * screen showed things like {@code ERROR: column "checksum" of relation "backup_registry" does
 * not exist} or {@code canceling statement due to statement timeout}. The technical detail is
 * still written to the log; this is what the person sees.
 *
 * <p>Messages the application raises deliberately — a {@code TradeRakshakException} or a
 * validation message written for a user — are already plain, so they are passed through intact.
 */
public final class UserMessage {

    private UserMessage() {}

    /** Plain-language sentence for a failure, safe to show on screen. */
    public static String of(Throwable ex) {
        return of(ex, "The action could not be completed.");
    }

    /**
     * @param fallback what to say when nothing more specific is recognised; write it as the
     *                 outcome the user cares about, e.g. "The backup could not be created."
     */
    public static String of(Throwable ex, String fallback) {
        String detail = deepestMessage(ex);
        if (detail.isEmpty()) return fallback;

        String lower = detail.toLowerCase();

        // Checked first, because it must beat the generic "timeout"/"connection" cases below.
        // A block read/write failure means PostgreSQL cannot reach its own files - typically the
        // drive holding them went offline (a laptop entering Modern Standby does exactly this) and
        // the running server was left holding stale handles. Retrying cannot help. Saying "please
        // try again" here is actively misleading: on 2026-08-25 it sent the user to the login screen
        // looking for an auth fault when the real problem was storage.
        if (lower.contains("could not read block") || lower.contains("could not write block")
                || lower.contains("could not open file") || lower.contains("could not read blocks")) {
            return "The database cannot read its own files, so nothing can be loaded. This usually "
                 + "means the drive holding the data went offline - a laptop going to sleep will do "
                 + "it. Restarting PostgreSQL normally fixes it, and the stored data is not lost. "
                 + "Retrying here will not help.";
        }

        if (lower.contains("out of memory")) {
            return "The server ran out of memory handling this request. It usually means too much "
                 + "data at once — try a smaller date range or fewer symbols.";
        }
        if (lower.contains("statement timeout") || lower.contains("canceling statement")) {
            return "This took too long and was stopped so the app stays responsive. Wait for any "
                 + "running job to finish, then try again.";
        }
        if (lower.contains("lock timeout") || lower.contains("could not obtain lock")
                || lower.contains("could not serialize")) {
            return "Another task is using the same records right now. Wait a few seconds and try again.";
        }
        if (lower.contains("does not exist") && lower.contains("column")) {
            return "The app tried to use a field that is missing from the database. This needs a fix "
                 + "in the app — please report it with the time it happened.";
        }
        if (lower.contains("does not exist") && lower.contains("relation")) {
            return "A table the app needs is missing from the database. This needs a fix in the app — "
                 + "please report it with the time it happened.";
        }
        if (lower.contains("duplicate key")) {
            return "That record already exists, so it was not added again.";
        }
        if (lower.contains("no space left") || lower.contains("disk full")) {
            return "The disk is full. Free some space — old files in the backups folder are a good place to start.";
        }
        if (lower.contains("connection refused") || lower.contains("connection is closed")
                || lower.contains("connection reset")) {
            return "The app could not reach the database. Check that the database is running, then try again.";
        }
        if (lower.contains("read timed out") || lower.contains("connect timed out")
                || lower.contains("timeout")) {
            return "The broker did not respond in time. This is usually temporary — please try again in a moment.";
        }
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("dh-901")
                || lower.contains("invalid_authentication")) {
            return "The broker rejected the saved login. Reconnect your broker in the Broker screen, then try again.";
        }
        if (lower.contains("dh-907") || lower.contains("no data present")) {
            return "The broker has no data for that selection yet. Daily prices are published after the "
                 + "market session settles — try again later or pick an earlier date.";
        }
        if (lower.contains("dh-905")) {
            return "The broker does not support one or more of these symbols. They have been skipped.";
        }
        if (lower.contains("429") || lower.contains("rate limit") || lower.contains("too many requests")) {
            return "The broker is limiting how fast we can ask for data. The app will slow down and retry.";
        }

        // Anything already written for a person (no stack-trace or SQL shape) passes through.
        if (looksHumanWritten(detail)) return detail;
        return fallback;
    }

    private static boolean looksHumanWritten(String detail) {
        if (detail.length() > 160) return false;
        String lower = detail.toLowerCase();
        return !lower.startsWith("error:")
            && !lower.contains("exception")
            && !lower.contains("sqlstate")
            && !lower.contains("nested")
            && !lower.contains("com.traderakshak")
            && !lower.contains("org.springframework")
            && !lower.contains("java.lang")
            && !detail.contains("\n");
    }

    private static String deepestMessage(Throwable ex) {
        String found = "";
        int depth = 0;
        for (Throwable c = ex; c != null && depth < 12; c = c.getCause(), depth++) {
            if (c.getMessage() != null && !c.getMessage().isBlank()) found = c.getMessage();
            if (c.getCause() == c) break;
        }
        return found == null ? "" : found.trim();
    }
}
