package io.selene.launcher.core.model;

import java.util.Objects;

/** Per-instance launch preferences kept beside the instance metadata. */
public record InstanceSettings(
        int minimumMemoryMb,
        int maximumMemoryMb,
        int width,
        int height,
        boolean fullscreen,
        String javaExecutable,
        String jvmArguments,
        String gameArguments,
        String quickServerAddress
) {
    public InstanceSettings {
        minimumMemoryMb = Math.clamp(minimumMemoryMb, 256, 32768);
        maximumMemoryMb = Math.clamp(maximumMemoryMb, minimumMemoryMb, 65536);
        width = Math.clamp(width, 640, 7680);
        height = Math.clamp(height, 360, 4320);
        javaExecutable = clean(javaExecutable);
        jvmArguments = clean(jvmArguments);
        gameArguments = clean(gameArguments);
        quickServerAddress = clean(quickServerAddress);
    }

    public static InstanceSettings defaults() {
        long physicalMb = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        int recommended = (int) Math.clamp(physicalMb, 2048, 4096);
        return new InstanceSettings(512, recommended, 1280, 720, false, "", "", "", "");
    }

    private static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
