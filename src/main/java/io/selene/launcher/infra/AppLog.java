package io.selene.launcher.infra;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Central launcher log with rotation and uncaught-exception capture. */
public final class AppLog {
    private static final Logger LOGGER = Logger.getLogger("io.selene.launcher");
    private static volatile Path directory;
    private static volatile boolean initialized;

    private AppLog() {
    }

    public static synchronized void initialize(Path logsDirectory) {
        if (initialized) return;
        directory = logsDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
            FileHandler handler = new FileHandler(
                    directory.resolve("launcher-%g.log").toString(),
                    4 * 1024 * 1024,
                    5,
                    true
            );
            handler.setEncoding(StandardCharsets.UTF_8.name());
            handler.setLevel(Level.ALL);
            handler.setFormatter(new LauncherFormatter());
            LOGGER.setUseParentHandlers(false);
            LOGGER.setLevel(Level.ALL);
            LOGGER.addHandler(handler);
            initialized = true;
            info("启动器日志已初始化：" + directory);
        } catch (IOException exception) {
            System.err.println("无法初始化启动器日志：" + exception.getMessage());
        }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            error("线程发生未捕获异常：" + thread.getName(), failure);
            if (previous != null) previous.uncaughtException(thread, failure);
        });
    }

    public static Path directory() {
        return directory;
    }

    public static void info(String message) {
        LOGGER.info(message);
    }

    public static void warning(String message) {
        LOGGER.warning(message);
    }

    public static void warning(String message, Throwable failure) {
        LOGGER.log(Level.WARNING, message, failure);
    }

    public static void error(String message, Throwable failure) {
        LOGGER.log(Level.SEVERE, message, failure);
    }

    private static final class LauncherFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            StringBuilder line = new StringBuilder(256)
                    .append(Instant.ofEpochMilli(record.getMillis()))
                    .append(" [").append(record.getLevel().getName()).append("] [")
                    .append(Thread.currentThread().getName()).append("] ")
                    .append(formatMessage(record)).append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                line.append(trace);
            }
            return line.toString();
        }
    }
}
