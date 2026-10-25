package io.selene.launcher.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.selene.launcher.core.model.GameInstance;
import io.selene.launcher.infra.AppLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Diagnoses mod dependency/version failures and resolves compatible replacements from Modrinth. */
public final class ModRepairService {
    private static final long MAX_LOG_BYTES = 8L * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final Pattern INSTALL = Pattern.compile(
            "(?im)^\\s*[-•]?\\s*Install\\s+(?:mod\\s+)?['\"]?([A-Za-z0-9_.-]+)['\"]?\\s*,?\\s*(?:version\\s+)?([^\\r\\n]+?)\\s*$");
    private static final Pattern REPLACE = Pattern.compile(
            "(?im)^\\s*[-•]?\\s*Replace\\s+mod\\s+['\"]([^'\"]+)['\"]\\s+\\(([^)]+)\\)\\s+(?:version\\s+)?([^\\s]+)\\s+with\\s+(?:mod\\s+)?['\"]?([^'\"\\s,]+)?['\"]?\\s*(?:\\([^)]*\\)\\s*)?(?:version\\s+)?(.+?)(?:\\.|$)");
    private static final Pattern FABRIC_MISSING = Pattern.compile(
            "(?i)requires\\s+(?:any\\s+)?(?:version\\s+)?(.+?)\\s+of\\s+(?:mod\\s+)?['\"]?([A-Za-z0-9_.-]+)['\"]?\\s*,?\\s*(?:which\\s+is\\s+missing|but\\s+it\\s+is\\s+missing|which\\s+is\\s+not\\s+installed)");
    private static final Pattern FABRIC_WRONG = Pattern.compile(
            "(?i)requires\\s+(?:any\\s+)?(?:version\\s+)?(.+?)\\s+of\\s+(?:mod\\s+)?['\"]?([A-Za-z0-9_.-]+)['\"]?\\s*,?\\s*but\\s+(?:only\\s+)?(?:the\\s+)?wrong\\s+version.*?(?:present|installed)(?:\\s*:?\\s*([^\\r\\n]+))?");
    private static final Pattern FORGE_DEPENDENCY = Pattern.compile(
            "(?is)Mod ID:\\s*['\"]?([A-Za-z0-9_.-]+)['\"]?.{0,300}?Expected range:\\s*['\"]?([^'\"\\r\\n]+)['\"]?.{0,200}?Actual version:\\s*['\"]?([^'\"\\r\\n]+)['\"]?");
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern TOML_VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern DISPLAY_NAME = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final List<String> NON_DOWNLOADABLE = List.of(
            "minecraft", "java", "fabricloader", "fabric-loader", "forge", "neoforge", "quilt_loader"
    );

    private final VanillaGameService games;
    private final ModrinthService modrinth;
    private final ObjectMapper mapper = new ObjectMapper();

    public ModRepairService(VanillaGameService games, ModrinthService modrinth) {
        this.games = games;
        this.modrinth = modrinth;
    }

    public CompletableFuture<List<RepairOption>> findRepairs(GameInstance instance) {
        final List<ModIssue> issues;
        try {
            issues = diagnose(instance);
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        List<CompletableFuture<RepairOption>> lookups = issues.stream().map(issue -> {
            if (NON_DOWNLOADABLE.contains(issue.modId().toLowerCase(Locale.ROOT))) {
                return CompletableFuture.completedFuture(new RepairOption(issue, Optional.empty(),
                        "该项属于游戏或加载器组件，不能作为普通模组自动替换"));
            }
            return modrinth.findCompatibleMod(issue.modId(), games.baseGameVersion(instance), games.loader(instance))
                    .handle((project, failure) -> {
                        if (failure != null) {
                            return new RepairOption(issue, Optional.empty(), "查询失败：" + rootCause(failure));
                        }
                        if (project.isEmpty()) {
                            return new RepairOption(issue, Optional.empty(), "没有找到与当前实例兼容的可用模组");
                        }
                        String action = issue.type() == IssueType.MISSING_DEPENDENCY ? "可一键下载 " : "可一键替换为 ";
                        return new RepairOption(issue, project, action + project.get().title());
                    });
        }).toList();
        return CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> lookups.stream().map(CompletableFuture::join).toList());
    }

    public CompletableFuture<Path> repair(GameInstance instance, RepairOption option) {
        ModrinthService.Project project = option.project().orElseThrow(
                () -> new IllegalArgumentException("该问题没有找到可用模组"));
        Path replaced = option.issue().type() == IssueType.WRONG_VERSION ? option.issue().sourceFile() : null;
        return modrinth.installRepair(project, instance, games.baseGameVersion(instance), games.loader(instance), replaced);
    }

    public List<ModIssue> diagnose(GameInstance instance) throws IOException {
        Map<String, LocalMod> localMods = inspectLocalMods(instance.directory().resolve("game/mods"));
        String log = collectLogs(instance);
        Map<String, ModIssue> issues = new LinkedHashMap<>();
        parseInstallRecommendations(log, localMods, issues);
        parseReplaceRecommendations(log, localMods, issues);
        parseFabricDependencies(log, localMods, issues);
        parseForgeDependencies(log, localMods, issues);
        List<ModIssue> result = issues.values().stream()
                .sorted(Comparator.comparing(ModIssue::type).thenComparing(ModIssue::modId, String.CASE_INSENSITIVE_ORDER))
                .toList();
        AppLog.info("模组诊断完成：" + instance.id() + "，发现 " + result.size() + " 项问题");
        return result;
    }

    public static boolean looksLikeModProblem(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("incompatible mod set") || lower.contains("mod resolution encountered")
                || lower.contains("missing or unsupported mandatory dependencies")
                || lower.contains("requires version") && (lower.contains("which is missing") || lower.contains("wrong version"))
                || lower.contains("failure message:") && lower.contains("requires");
    }

    private void parseInstallRecommendations(String log, Map<String, LocalMod> local, Map<String, ModIssue> issues) {
        Matcher matcher = INSTALL.matcher(log);
        while (matcher.find()) {
            String id = normalizeId(matcher.group(1));
            String required = cleanVersion(matcher.group(2));
            addIssue(issues, new ModIssue(IssueType.MISSING_DEPENDENCY, id, id, "", required,
                    null, abbreviate(matcher.group(), 600)));
        }
    }

    private void parseReplaceRecommendations(String log, Map<String, LocalMod> local, Map<String, ModIssue> issues) {
        Matcher matcher = REPLACE.matcher(log);
        while (matcher.find()) {
            String id = normalizeId(matcher.group(2));
            LocalMod installed = local.get(id.toLowerCase(Locale.ROOT));
            String targetId = normalizeId(matcher.group(4));
            if (targetId.isBlank() || targetId.equalsIgnoreCase("version")) targetId = id;
            String required = cleanVersion(matcher.group(5));
            addIssue(issues, new ModIssue(IssueType.WRONG_VERSION, targetId, matcher.group(1), matcher.group(3), required,
                    installed == null ? null : installed.path(), abbreviate(matcher.group(), 600)));
        }
    }

    private void parseFabricDependencies(String log, Map<String, LocalMod> local, Map<String, ModIssue> issues) {
        Matcher missing = FABRIC_MISSING.matcher(log);
        while (missing.find()) {
            String id = normalizeId(missing.group(2));
            addIssue(issues, new ModIssue(IssueType.MISSING_DEPENDENCY, id, id, "", cleanVersion(missing.group(1)),
                    null, abbreviate(missing.group(), 600)));
        }
        Matcher wrong = FABRIC_WRONG.matcher(log);
        while (wrong.find()) {
            String id = normalizeId(wrong.group(2));
            LocalMod installed = local.get(id.toLowerCase(Locale.ROOT));
            addIssue(issues, new ModIssue(IssueType.WRONG_VERSION, id,
                    installed == null ? id : installed.name(), installed == null ? value(wrong.group(3)) : installed.version(),
                    cleanVersion(wrong.group(1)), installed == null ? null : installed.path(), abbreviate(wrong.group(), 600)));
        }
    }

    private void parseForgeDependencies(String log, Map<String, LocalMod> local, Map<String, ModIssue> issues) {
        Matcher matcher = FORGE_DEPENDENCY.matcher(log);
        while (matcher.find()) {
            String id = normalizeId(matcher.group(1));
            String actual = value(matcher.group(3));
            boolean missing = actual.toUpperCase(Locale.ROOT).contains("MISSING") || actual.isBlank();
            LocalMod installed = local.get(id.toLowerCase(Locale.ROOT));
            addIssue(issues, new ModIssue(missing ? IssueType.MISSING_DEPENDENCY : IssueType.WRONG_VERSION,
                    id, installed == null ? id : installed.name(), installed == null ? actual : installed.version(),
                    cleanVersion(matcher.group(2)), installed == null ? null : installed.path(), abbreviate(matcher.group(), 600)));
        }
    }

    private void addIssue(Map<String, ModIssue> issues, ModIssue issue) {
        if (issue.modId().isBlank()) return;
        String key = issue.type() + ":" + issue.modId().toLowerCase(Locale.ROOT);
        issues.putIfAbsent(key, issue);
    }

    private Map<String, LocalMod> inspectLocalMods(Path directory) throws IOException {
        if (Files.notExists(directory)) return Map.of();
        Map<String, LocalMod> mods = new LinkedHashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                try {
                    LocalMod mod = readLocalMod(path);
                    if (mod != null && !mod.id().isBlank()) mods.putIfAbsent(mod.id().toLowerCase(Locale.ROOT), mod);
                } catch (IOException | RuntimeException failure) {
                    AppLog.warning("无法读取模组元数据：" + path, failure);
                }
            }
        }
        return mods;
    }

    private LocalMod readLocalMod(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null) {
                JsonNode root = mapper.readTree(readEntry(zip, fabric));
                return new LocalMod(root.path("id").asText(""), root.path("name").asText(root.path("id").asText("")),
                        root.path("version").asText(""), path);
            }
            ZipEntry forge = zip.getEntry("META-INF/mods.toml");
            if (forge == null) forge = zip.getEntry("META-INF/neoforge.mods.toml");
            if (forge == null) return null;
            String toml = new String(readEntry(zip, forge), StandardCharsets.UTF_8);
            Matcher id = MOD_ID.matcher(toml);
            if (!id.find()) return null;
            Matcher version = TOML_VERSION.matcher(toml);
            Matcher name = DISPLAY_NAME.matcher(toml);
            return new LocalMod(id.group(1), name.find() ? name.group(1) : id.group(1),
                    version.find() ? version.group(1) : "", path);
        }
    }

    private byte[] readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > MAX_METADATA_BYTES) throw new IOException("模组元数据超过安全限制");
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
            if (bytes.length > MAX_METADATA_BYTES) throw new IOException("模组元数据超过安全限制");
            return bytes;
        }
    }

    private String collectLogs(GameInstance instance) throws IOException {
        List<Path> files = new ArrayList<>();
        Path latest = instance.directory().resolve("logs/latest.log");
        if (Files.isRegularFile(latest)) files.add(latest);
        Path crashDirectory = instance.directory().resolve("game/crash-reports");
        if (Files.isDirectory(crashDirectory)) {
            try (var paths = Files.list(crashDirectory)) {
                paths.filter(Files::isRegularFile).max(Comparator.comparingLong(this::lastModified)).ifPresent(files::add);
            }
        }
        if (files.isEmpty()) throw new IOException("没有找到可供诊断的启动日志或崩溃报告");
        StringBuilder result = new StringBuilder();
        for (Path file : files) {
            result.append("\n===== ").append(file.getFileName()).append(" =====\n");
            result.append(readTail(file));
        }
        return result.toString();
    }

    private String readTail(Path file) throws IOException {
        long size = Files.size(file);
        if (size <= MAX_LOG_BYTES) return Files.readString(file);
        byte[] bytes = new byte[(int) MAX_LOG_BYTES];
        try (InputStream input = Files.newInputStream(file)) {
            input.skipNBytes(size - MAX_LOG_BYTES);
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(bytes, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private String normalizeId(String value) {
        return value(value).trim().replace("'", "").replace("\"", "");
    }

    private String cleanVersion(String value) {
        return value(value).replaceAll("(?i)\\s+(?:which|but)\\s+.*$", "")
                .replaceAll("[.!]+$", "").trim();
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private String abbreviate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    private String rootCause(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException) && current.getCause() != null) current = current.getCause();
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public enum IssueType {
        MISSING_DEPENDENCY("缺失依赖"), WRONG_VERSION("版本错误");
        private final String displayName;
        IssueType(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }

    public record ModIssue(
            IssueType type, String modId, String displayName, String installedVersion,
            String requiredVersion, Path sourceFile, String detail
    ) { }

    public record RepairOption(ModIssue issue, Optional<ModrinthService.Project> project, String status) {
        public RepairOption {
            project = project == null ? Optional.empty() : project;
        }
        public boolean available() { return project.isPresent(); }
    }

    private record LocalMod(String id, String name, String version, Path path) { }
}
