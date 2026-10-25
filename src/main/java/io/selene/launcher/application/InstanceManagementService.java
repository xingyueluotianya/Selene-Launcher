package io.selene.launcher.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.selene.launcher.core.model.GameInstance;
import io.selene.launcher.core.model.InstanceSettings;
import io.selene.launcher.core.model.ManagedMod;
import io.selene.launcher.infra.AppLog;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Manages mutable instance metadata, launch preferences and local mod files. */
public final class InstanceManagementService {
    private static final String DISABLED_SUFFIX = ".disabled";
    private final VanillaGameService games;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public InstanceManagementService(VanillaGameService games) {
        this.games = games;
    }

    public List<GameInstance> instances() {
        return games.installedInstances().stream()
                .sorted(Comparator.comparing(GameInstance::installedAt).reversed())
                .toList();
    }

    public GameInstance rename(GameInstance instance, String requestedName) throws IOException {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isBlank()) throw new IllegalArgumentException("实例名称不能为空");
        if (name.length() > 64) throw new IllegalArgumentException("实例名称不能超过 64 个字符");
        Path metadataFile = instance.directory().resolve("instance.json");
        ObjectNode metadata = (ObjectNode) mapper.readTree(metadataFile.toFile());
        metadata.put("name", name);
        atomicWrite(metadataFile, metadata);
        AppLog.info("实例已重命名：" + instance.id() + " -> " + name);
        return new GameInstance(instance.id(), name, instance.versionId(), instance.directory(),
                instance.mainClass(), instance.requiredJavaMajor(), instance.installedAt());
    }

    public void delete(GameInstance instance) throws IOException {
        Path instancesRoot = games.instancesDirectory().toAbsolutePath().normalize();
        Path directory = instance.directory().toAbsolutePath().normalize();
        if (directory.equals(instancesRoot) || !directory.startsWith(instancesRoot)) {
            throw new IOException("拒绝删除实例目录之外的路径：" + directory);
        }
        if (Files.notExists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
        AppLog.info("实例已删除：" + instance.id());
    }

    public InstanceSettings loadSettings(GameInstance instance) {
        Path file = settingsFile(instance);
        if (Files.notExists(file)) return InstanceSettings.defaults();
        try {
            return mapper.readValue(file.toFile(), InstanceSettings.class);
        } catch (IOException | RuntimeException failure) {
            AppLog.warning("实例设置损坏，将使用默认值：" + instance.id(), failure);
            return InstanceSettings.defaults();
        }
    }

    public void saveSettings(GameInstance instance, InstanceSettings settings) throws IOException {
        ObjectNode node = mapper.valueToTree(settings);
        node.put("schemaVersion", 1);
        atomicWrite(settingsFile(instance), node);
        AppLog.info("实例设置已保存：" + instance.id());
    }

    public List<ManagedMod> mods(GameInstance instance) throws IOException {
        Path directory = modsDirectory(instance);
        Files.createDirectories(directory);
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::isModFile)
                    .map(path -> {
                        try {
                            String fileName = path.getFileName().toString();
                            return new ManagedMod(path, enabledName(fileName), Files.size(path),
                                    !fileName.toLowerCase(Locale.ROOT).endsWith(DISABLED_SUFFIX));
                        } catch (IOException failure) {
                            AppLog.warning("无法读取模组文件：" + path, failure);
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(ManagedMod::fileName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public Path importMod(GameInstance instance, Path source) throws IOException {
        Path normalized = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("只能导入 .jar 模组文件");
        }
        Path targetDirectory = modsDirectory(instance);
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve(normalized.getFileName().toString()).normalize();
        if (!target.startsWith(targetDirectory.toAbsolutePath().normalize())) throw new IOException("模组文件名包含不安全路径");
        Files.copy(normalized, target, StandardCopyOption.REPLACE_EXISTING);
        AppLog.info("已导入模组：" + target);
        return target;
    }

    public ManagedMod setEnabled(ManagedMod mod, boolean enabled) throws IOException {
        if (mod.enabled() == enabled) return mod;
        String current = mod.path().getFileName().toString();
        String nextName = enabled ? enabledName(current) : current + DISABLED_SUFFIX;
        Path target = mod.path().resolveSibling(nextName);
        if (Files.exists(target)) throw new IOException("目标模组文件已存在：" + target.getFileName());
        Files.move(mod.path(), target);
        AppLog.info((enabled ? "已启用模组：" : "已禁用模组：") + mod.fileName());
        return new ManagedMod(target, enabledName(nextName), Files.size(target), enabled);
    }

    public void deleteMod(ManagedMod mod) throws IOException {
        Files.deleteIfExists(mod.path());
        AppLog.info("已删除模组：" + mod.fileName());
    }

    public Path gameDirectory(GameInstance instance) {
        return instance.directory().resolve("game");
    }

    public Path modsDirectory(GameInstance instance) {
        return gameDirectory(instance).resolve("mods");
    }

    private Path settingsFile(GameInstance instance) {
        return instance.directory().resolve("settings.json");
    }

    private boolean isModFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".jar" + DISABLED_SUFFIX);
    }

    private String enabledName(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(DISABLED_SUFFIX)
                ? fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length()) : fileName;
    }

    private void atomicWrite(Path target, JsonNode value) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writeValue(temporary.toFile(), value);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
