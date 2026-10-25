package io.selene.launcher;

/**
 * Non-JavaFX entry point. Keeping this class separate makes packaged launches
 * independent from the JavaFX launcher's class-path detection.
 */
public final class LauncherMain {
    private LauncherMain() {
    }

    public static void main(String[] args) {
        StartupFailureReporter.install();
        try {
            StartupGuard.verify();
            if (isStartupCheck(args)) {
                System.out.println("Selene Launcher startup check passed.");
                return;
            }
            SeleneLauncherApp.main(args);
        } catch (Throwable failure) {
            StartupFailureReporter.report(failure, "main");
            System.exit(1);
        }
    }

    private static boolean isStartupCheck(String[] args) {
        if (args == null) return false;
        for (String argument : args) {
            if ("--startup-check".equals(argument)) return true;
        }
        return false;
    }
}
