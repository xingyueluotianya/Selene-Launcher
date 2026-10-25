package io.selene.launcher;

import java.util.ArrayList;
import java.util.List;

/** Performs dependency and resource checks before JavaFX takes control of the process. */
public final class StartupGuard {
    private static final int REQUIRED_JAVA_FEATURE = 21;
    private static final List<String> REQUIRED_CLASSES = List.of(
            "javafx.application.Application",
            "javafx.scene.control.Control",
            "com.fasterxml.jackson.databind.ObjectMapper"
    );
    private static final List<String> REQUIRED_RESOURCES = List.of(
            "io/selene/launcher/SeleneLauncherApp.class",
            "io/selene/launcher/selene.css",
            "io/selene/launcher/selene-icon.png"
    );

    private StartupGuard() {
    }

    public static void verify() {
        List<String> problems = inspect(StartupGuard.class.getClassLoader(), Runtime.version().feature());
        if (!problems.isEmpty()) {
            throw new IllegalStateException("启动环境自检失败：" + String.join("；", problems));
        }
    }

    static List<String> inspect(ClassLoader loader, int javaFeature) {
        List<String> problems = new ArrayList<>();
        if (javaFeature < REQUIRED_JAVA_FEATURE) {
            problems.add("需要 Java 21，当前为 Java " + javaFeature);
        }
        for (String className : REQUIRED_CLASSES) {
            try {
                Class.forName(className, false, loader);
            } catch (ClassNotFoundException | LinkageError failure) {
                problems.add("缺少运行依赖 " + className);
            }
        }
        for (String resource : REQUIRED_RESOURCES) {
            if (loader.getResource(resource) == null) {
                problems.add("缺少应用资源 " + resource);
            }
        }
        return List.copyOf(problems);
    }
}
