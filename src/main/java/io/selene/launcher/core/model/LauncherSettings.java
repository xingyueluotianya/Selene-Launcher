package io.selene.launcher.core.model;

import java.util.Objects;

public record LauncherSettings(
        String locale,
        String theme,
        int maxConcurrentDownloads,
        boolean playIntro,
        boolean preferMirror,
        String activeProfileId,
        boolean onboardingCompleted,
        String downloadsDirectory,
        String instancesDirectory,
        String sharedMinecraftDirectory,
        String runtimesDirectory
) {
    public LauncherSettings {
        locale = AppLanguage.fromTag(locale).tag();
        theme = Objects.requireNonNullElse(theme, "midnight");
        maxConcurrentDownloads = Math.clamp(maxConcurrentDownloads, 1, 16);
        activeProfileId = Objects.requireNonNullElse(activeProfileId, "default");
        downloadsDirectory = clean(downloadsDirectory);
        instancesDirectory = clean(instancesDirectory);
        sharedMinecraftDirectory = clean(sharedMinecraftDirectory);
        runtimesDirectory = clean(runtimesDirectory);
    }

    public static LauncherSettings defaults() {
        return new LauncherSettings(
                AppLanguage.ENGLISH.tag(),
                "midnight",
                4,
                true,
                false,
                "default",
                false,
                "",
                "",
                "",
                ""
        );
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
