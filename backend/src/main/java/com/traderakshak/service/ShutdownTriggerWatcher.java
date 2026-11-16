package com.traderakshak.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets STOP.bat ask the application to shut down in an orderly way.
 *
 * <p>Windows has no SIGTERM. STOP.bat used to run {@code taskkill /PID} without {@code /F},
 * on the understanding that this is the polite request and {@code /F} is the forced kill. It
 * is - but what it actually does is post WM_CLOSE to windows <em>owned by that process</em>,
 * and a JVM started as {@code java -jar} owns none. Measured on the running instance:
 *
 * <pre>
 *   PID 18708   MainWindowHandle : 0   MainWindowTitle : ''
 * </pre>
 *
 * <p>So the request reached nothing at all. STOP.bat waited its full 15 seconds, concluded the
 * JVM was wedged, and forced the kill - every single time, on six consecutive stops. The
 * {@code @PreDestroy} flush in {@link LiveCandleAggregator} therefore never ran, and since
 * bars are 60 minutes wide, each stop discarded the open bar for every tracked symbol.
 *
 * <p>Closing the START window did work, because that sends CTRL_CLOSE_EVENT to the console and
 * the JVM turns it into an orderly shutdown. Only the STOP.bat path was losing data, which is
 * the opposite of what the two look like.
 *
 * <p>A file is used rather than an HTTP shutdown endpoint deliberately. Actuator could expose
 * one, but it would be an unauthenticated local endpoint that stops the application, reachable
 * by a form POST from any page in the user's browser. A file in the application's own runtime
 * directory needs no listener, no authentication and no open port, and works the same whether
 * the app was launched from a console window or headlessly.
 */
@Component
public class ShutdownTriggerWatcher {

    private static final Logger log = LoggerFactory.getLogger(ShutdownTriggerWatcher.class);

    /** Written by STOP.bat; its contents are irrelevant, only its presence matters. */
    static final String TRIGGER_NAME = ".shutdown";

    /**
     * A trigger older than this is ignored and removed.
     *
     * <p>The file is created immediately before the wait loop, so a live request is always
     * seconds old. An older one can only be a leftover from a run that was force-killed
     * between writing it and acting on it, and honouring that would shut the application down
     * moments after the user next started it - a far more confusing failure than ignoring it.
     */
    private static final Duration MAX_TRIGGER_AGE = Duration.ofMinutes(2);

    private final ConfigurableApplicationContext context;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public ShutdownTriggerWatcher(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /**
     * Where the trigger may appear.
     *
     * <p>START.bat and INSTALL.bat both launch with
     * {@code -Djava.io.tmpdir="%APP_DIR%runtime-tmp"}, so the first candidate is the
     * application's own runtime directory in the normal case. The second covers a JAR run
     * directly without that flag, where java.io.tmpdir would be the system temp folder and
     * would never see what STOP.bat wrote.
     */
    private Path[] candidates() {
        return new Path[]{
            Paths.get(System.getProperty("java.io.tmpdir", "."), TRIGGER_NAME),
            Paths.get("runtime-tmp", TRIGGER_NAME)
        };
    }

    @Scheduled(fixedDelay = 1000L, initialDelay = 10_000L)
    public void pollForShutdownRequest() {
        for (Path trigger : candidates()) {
            try {
                if (!Files.isRegularFile(trigger)) continue;

                Instant written = Files.getLastModifiedTime(trigger).toInstant();
                if (Duration.between(written, Instant.now()).compareTo(MAX_TRIGGER_AGE) > 0) {
                    log.warn("[Shutdown] Ignoring a stale stop request at {} (written {}). "
                           + "Removing it so it cannot fire later.", trigger, written);
                    deleteQuietly(trigger);
                    continue;
                }

                if (!shuttingDown.compareAndSet(false, true)) return;

                // Delete before shutting down, not after: if this JVM is killed mid-shutdown the
                // file must not survive to stop the next start.
                deleteQuietly(trigger);
                log.info("[Shutdown] Stop requested via {}. Closing down - open candles will be "
                       + "written by the shutdown hooks before the process exits.", trigger);
                closeAsync();
                return;
            } catch (IOException e) {
                log.debug("[Shutdown] Could not examine {}: {}", trigger, e.getMessage());
            }
        }
    }

    /**
     * Closes the context from a thread of its own.
     *
     * <p>Shutting down stops the scheduler, and the scheduler waits for its running tasks to
     * finish - so closing from inside one of those tasks would have each waiting on the other.
     * The thread is deliberately non-daemon so it cannot be killed halfway through the flush.
     */
    private void closeAsync() {
        Thread closer = new Thread(() -> {
            int code = 0;
            try {
                code = SpringApplication.exit(context, () -> 0);
            } catch (Exception e) {
                log.warn("[Shutdown] Orderly shutdown failed: {}", e.getMessage());
            } finally {
                System.exit(code);
            }
        }, "shutdown-trigger");
        closer.setDaemon(false);
        closer.start();
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("[Shutdown] Could not remove {}: {}. Remove it by hand, or the next start "
                   + "may stop immediately.", p, e.getMessage());
        }
    }
}
