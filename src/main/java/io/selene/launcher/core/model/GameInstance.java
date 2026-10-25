package io.selene.launcher.core.model;

import java.nio.file.Path;
import java.time.Instant;

public record GameInstance(
        String id,
        String name,
        String versionId,
        Path directory,
        String mainClass,
        int requiredJavaMajor,
        Instant installedAt
) {
}
