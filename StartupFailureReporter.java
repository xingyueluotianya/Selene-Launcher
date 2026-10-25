package io.selene.launcher;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Last-resort diagnostics for failures that happen before the JavaFX window appears. */
public final class StartupFailureReporter {
    private static volatile boolean armed;
    private static volatile boolean reporting;

    private StartupFailureReporter() {
    }

    public static void install() {
        armed = true;
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (armed) report(failure, thread.getName());
        });
    }

    public static void markStarted() {
        armed = false;
    }

    public static synchronized Path report(Throwable failure, String phase) {
        if (reporting) return null;
        reporting = true;
        try {
            Throwable cause = rootCause(failure);
            Path log = writeEmergencyLog(failure, phase);
            String detail = cause.getMessage() == null || cause.getMessage().isBlank()
                    ? cause.getClass().getName() : cause.getMessage();
            StringBuilder message = new StringBuilder("Selene Launcher 启动失败。\n\n原因：")
                    .append(detail);
            if (log != null) {
                message.append("\n\n诊断日志已写入：").append(log);
            } else {
                message.append("\n\n诊断日志写入失败，请运行同目录的 SeleneLauncher-Diagnostic.cmd。");
            }
            message.append("\n\n请把诊断日志发给开发者，不需要移动 EXE、JAR、lib 或 runtime。");
            showDialog(message.toString());
            return log;
        } finally {
            reporting = false;
        }
    }

    static Path writeEmergencyLog(Throwable failure, String phase) {
        return writeEmergencyLog(failure, phase, candidateDirectories());
    }

    static Path writeEmergencyLog(Throwable failure, String phase, List<Path> directories) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        String content = Instant.now() + " [SEVERE] 启动阶段：" + safe(phase) + System.lineSeparator()
                + "Java: " + System.getProperty("java.runtime.version", "unknown") + System.lineSeparator()
                + "OS: " + System.getProperty("os.name", "unknown") + " "
                + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", "")
                + System.lineSeparator() + trace + System.lineSeparator();
        for (Path directory : directories) {
            try {
                Files.createDirectories(directory);
                Path log = directory.resolve("startup-failure.log");
                Files.writeString(log, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return log.toAbsolutePath().normalize();
            } catch (Exception ignored) {
                // Try the next writable location.
            }
        }
        System.err.print(content);
        return null;
    }

    private static List<Path> candidateDirectories() {
        List<Path> directories = new ArrayList<>();
        String workingDirectory = System.getProperty("user.dir");
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            tryAdd(directories, workingDirectory);
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            tryAdd(directories, appData, "SeleneLauncher", "logs");
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            tryAdd(directories, home, ".selene-launcher", "logs");
        }
        String temporary = System.getProperty("java.io.tmpdir");
        if (temporary != null && !temporary.isBlank()) {
            tryAdd(directories, temporary, "SeleneLauncher", "logs");
        }
        return directories;
    }

    private static void tryAdd(List<Path> directories, String first, String... more) {
        try {
            directories.add(Path.of(first, more));
        } catch (RuntimeException ignored) {
            // Invalid environment paths must not hide the original startup failure.
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure == null ? new IllegalStateException("未知启动错误") : failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static void showDialog(String message) {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, message, "Selene Launcher 启动失败", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Throwable dialogFailure) {
            System.err.println(message);
        }
    }
}
