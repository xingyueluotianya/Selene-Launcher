package io.selene.launcher.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.selene.launcher.core.model.VisualTheme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class VisualThemeRepository {
    private final Path file;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public VisualThemeRepository(Path file) {
        this.file = file;
    }

    public VisualTheme load() {
        if (Files.notExists(file)) return VisualTheme.moonSea();
        try {
            return objectMapper.readValue(file.toFile(), VisualTheme.class);
        } catch (IOException exception) {
            AppLog.warning("无法读取视觉主题，将使用月海预设", exception);
            return VisualTheme.moonSea();
        }
    }

    public void save(VisualTheme theme) throws IOException {
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        objectMapper.writeValue(temporary.toFile(), theme);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
