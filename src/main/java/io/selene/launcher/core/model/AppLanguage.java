package io.selene.launcher.core.model;

import java.util.Locale;

/** Supported launcher interface languages. */
public enum AppLanguage {
    ENGLISH("en-US", "English", "英文"),
    SIMPLIFIED_CHINESE("zh-CN", "Simplified Chinese", "简体中文");

    private final String tag;
    private final String englishName;
    private final String chineseName;

    AppLanguage(String tag, String englishName, String chineseName) {
        this.tag = tag;
        this.englishName = englishName;
        this.chineseName = chineseName;
    }

    public String tag() {
        return tag;
    }

    public String displayName(AppLanguage interfaceLanguage) {
        return interfaceLanguage == SIMPLIFIED_CHINESE ? chineseName : englishName;
    }

    public Locale locale() {
        return Locale.forLanguageTag(tag);
    }

    public static AppLanguage fromTag(String tag) {
        if (tag != null && tag.toLowerCase(Locale.ROOT).startsWith("zh")) {
            return SIMPLIFIED_CHINESE;
        }
        return ENGLISH;
    }
}
