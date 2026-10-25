package io.selene.launcher.core.model;

/** A loader release offered by its official metadata catalog. */
public record LoaderVersion(String version, boolean stable, String channel) {
    public LoaderVersion {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Loader version is required");
        channel = channel == null || channel.isBlank() ? (stable ? "稳定版" : "测试版") : channel;
    }

    @Override
    public String toString() {
        return version + "  ·  " + channel;
    }
}
