package io.selene.launcher.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.selene.launcher.core.model.LauncherSettings;
import io.selene.launcher.core.port.SettingsRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class JsonSettingsRepository implements SettingsRepository {
    private final Path settingsFile;
    private final ObjectMapper objectMapper;

    public JsonSettingsRepository(Path settingsFile) {
        this.settingsFile = settingsFile;
        this.objectMapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public LauncherSettings load() throws IOException {
        if (Files.notExists(settingsFile)) {
            return LauncherSettings.defaults();
        }
        return objectMapper.readValue(settingsFile.toFile(), LauncherSettings.class);
    }

    @Override
    public void save(LauncherSettings settings) throws IOException {
        Files.createDirectories(settingsFile.getParent());
        Path temporaryFile = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
        objectMapper.writeValue(temporaryFile.toFile(), settings);
        try {
            Files.move(temporaryFile, settingsFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailure) {
            Files.move(temporaryFile, settingsFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
