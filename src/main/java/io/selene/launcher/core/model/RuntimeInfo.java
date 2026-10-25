package io.selene.launcher.core.model;

import java.nio.file.Path;

public record RuntimeInfo(Path executable, int majorVersion, String vendor, Source source) {
    public enum Source {
        SELENE_MANAGED,
        SYSTEM,
        CUSTOM
    }
}
