package io.selene.launcher.application;

import io.selene.launcher.infra.AppLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

/** Converts installation and launch failures into concise, actionable diagnoses. */
public final class FailureDiagnosticService {
    private static final long MAX_LOG_BYTES = 8L * 1024 * 1024;

    public Diagnosis diagnoseInstallation(String target, Throwable failure) {
        Throwable cause = unwrap(failure);
        String evidence = message(cause);
        String text = (cause.getClass().getName() + " " + evidence).toLowerCase(Locale.ROOT);
        Diagnosis diagnosis;
        if (containsAny(text, "checksum", "sha-1", "sha1", "sha-256", "校验", "hash mismatch")) {
            diagnosis = new Diagnosis("下载文件校验失败",
                    target + " 的下载内容不完整，或缓存文件已损坏。", evidence,
                    List.of("在下载中心重试失败任务", "若仍失败，删除对应缓存后重新安装", "检查代理或镜像是否篡改下载内容"), true);
        } else if (containsAny(text, "http 403", "http 404", "http 429", "http 5", "timed out", "timeout",
                "connect", "connection", "unknownhost", "网络", "请求被中断")) {
            diagnosis = new Diagnosis("网络源暂时不可用",
                    target + " 未能从官方源取得所需文件。", evidence,
                    List.of("确认网络和系统时间正常", "稍后重试或在下载中心继续任务", "若使用代理，请确认 Java 可以访问该代理"), true);
        } else if (containsAny(text, "accessdenied", "permission denied", "拒绝访问", "只读", "read-only")) {
            diagnosis = new Diagnosis("存储目录没有写入权限",
                    "Selene Launcher 无法写入 " + target + " 所需文件。", evidence,
                    List.of("关闭占用文件的游戏或其他启动器", "在设置中选择可写入的存储目录", "检查安全软件是否拦截文件操作"), true);
        } else if (containsAny(text, "no space left", "磁盘空间", "空间不足", "disk full")) {
            diagnosis = new Diagnosis("磁盘空间不足", target + " 无法继续写入文件。", evidence,
                    List.of("释放实例盘和下载缓存盘空间", "在设置中把大型文件位置改到其他磁盘"), true);
        } else if (containsAny(text, "unsupportedclassversion", "java runtime", "java.exe", "javaw.exe", "java 运行时", "official installer exit")) {
            diagnosis = new Diagnosis("Java 或加载器安装器执行失败",
                    target + " 的安装器未能使用兼容 Java 正常完成。", evidence,
                    List.of("重试以重新校验自动管理的 Java", "检查安全软件是否阻止 java.exe", "导出诊断包以查看安装器完整输出"), true);
        } else if (containsAny(text, "metadata", "元数据", "缺少字段", "版本配置", "launcher_profiles")) {
            diagnosis = new Diagnosis("版本元数据无效",
                    target + " 的官方版本信息不完整或与所选版本不一致。", evidence,
                    List.of("刷新版本和 Loader 清单后重新选择", "确认 Loader 版本确实支持该 Minecraft 版本", "稍后重试官方源"), false);
        } else {
            diagnosis = new Diagnosis("安装失败，未匹配到已知原因",
                    target + " 未完成安装。启动器已保留原始错误供进一步排查。", evidence,
                    List.of("先在下载中心重试失败任务", "导出诊断包并附上本条错误", "确认存储目录、网络和安全软件状态"), false);
        }
        AppLog.warning("自动诊断 · " + diagnosis.title() + " · " + diagnosis.evidence(), cause);
        return diagnosis;
    }

    public Diagnosis diagnoseLaunch(Path logFile, int exitCode) {
        String log = readTail(logFile);
        String text = log.toLowerCase(Locale.ROOT);
        Diagnosis diagnosis;
        if (ModRepairService.looksLikeModProblem(log)) {
            diagnosis = new Diagnosis("检测到模组依赖或版本冲突",
                    "游戏退出代码为 " + exitCode + "。可前往“模组管理”扫描清单，并选择一键下载或替换。",
                    lastRelevantLine(log), List.of("扫描模组问题清单", "确认实例版本与 Loader 和模组一致", "修复后重新启动游戏"), false);
        } else if (containsAny(text, "outofmemoryerror", "could not reserve enough space", "insufficient memory")) {
            diagnosis = new Diagnosis("游戏内存设置不合适",
                    "游戏退出代码为 " + exitCode + "，日志显示 Java 内存分配失败。", lastRelevantLine(log),
                    List.of("降低实例最大内存", "关闭占用内存的程序", "确认使用 64 位 Java"), true);
        } else if (containsAny(text, "unsupportedclassversionerror", "class file version")) {
            diagnosis = new Diagnosis("Java 版本不兼容",
                    "游戏退出代码为 " + exitCode + "，某个组件需要不同的 Java 版本。", lastRelevantLine(log),
                    List.of("清空自定义 Java 路径并让启动器自动选择", "检查模组支持的 Minecraft 与 Java 版本"), true);
        } else if (containsAny(text, "failed to verify authentication", "invalid credentials", "access token")) {
            diagnosis = new Diagnosis("账号会话已失效", "游戏退出代码为 " + exitCode + "，账号认证未通过。",
                    lastRelevantLine(log), List.of("在账号中心重新登录", "确认系统时间正确"), true);
        } else {
            diagnosis = new Diagnosis("游戏异常退出",
                    "游戏退出代码为 " + exitCode + "，暂未匹配到可自动修复的已知问题。", lastRelevantLine(log),
                    List.of("查看启动日志末尾的 Caused by", "导出诊断包以便进一步排查"), false);
        }
        AppLog.warning("启动自动诊断 · " + diagnosis.title() + " · " + diagnosis.evidence());
        return diagnosis;
    }

    private String readTail(Path file) {
        if (file == null || !Files.isRegularFile(file)) return "未找到启动日志";
        try {
            long size = Files.size(file);
            if (size <= MAX_LOG_BYTES) return Files.readString(file);
            byte[] bytes = new byte[(int) MAX_LOG_BYTES];
            try (var input = Files.newInputStream(file)) {
                input.skipNBytes(size - MAX_LOG_BYTES);
                int offset = 0;
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                return new String(bytes, 0, offset, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException failure) {
            return "无法读取启动日志：" + failure.getMessage();
        }
    }

    private String lastRelevantLine(String log) {
        String[] lines = log.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (!line.isBlank() && (line.contains("Caused by") || line.contains("Exception") || line.contains("Error")
                    || line.toLowerCase(Locale.ROOT).contains("requires") || line.toLowerCase(Locale.ROOT).contains("missing"))) {
                return abbreviate(line, 900);
            }
        }
        return lines.length == 0 ? "日志为空" : abbreviate(lines[lines.length - 1].trim(), 900);
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure == null ? new IllegalStateException("未知错误") : failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        while (current.getCause() != null && current.getMessage() == null) current = current.getCause();
        return current;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private String message(Throwable failure) {
        String value = failure.getMessage();
        return abbreviate(value == null || value.isBlank() ? failure.getClass().getSimpleName() : value, 1600);
    }

    private String abbreviate(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    public record Diagnosis(
            String title, String summary, String evidence, List<String> recommendations, boolean retryable
    ) {
        public Diagnosis {
            recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        }

        public String displayText() {
            List<String> lines = new ArrayList<>();
            lines.add(title);
            lines.add(summary);
            if (evidence != null && !evidence.isBlank()) lines.add("检测依据：" + evidence);
            if (!recommendations.isEmpty()) {
                lines.add("建议：");
                recommendations.forEach(item -> lines.add("• " + item));
            }
            return String.join(System.lineSeparator(), lines);
        }
    }
}
