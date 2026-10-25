package io.selene.launcher.core.model;

public enum GameEdition {
    JAVA("Java Edition"),
    BEDROCK("Bedrock Edition");

    private final String displayName;

    GameEdition(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
