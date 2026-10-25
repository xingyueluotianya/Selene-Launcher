package io.selene.launcher.core.model;

import java.nio.file.Path;

/** A local mod file and whether the launcher currently exposes it to the game. */
public record ManagedMod(Path path, String fileName, long size, boolean enabled) {
}
