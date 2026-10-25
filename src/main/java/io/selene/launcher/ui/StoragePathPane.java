package io.selene.launcher.ui;

import io.selene.launcher.core.model.LauncherSettings;
import io.selene.launcher.infra.AppPaths;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

/** Reusable editor for large-data locations used by onboarding and settings. */
final class StoragePathPane {
    private final Path launcherRoot;
    private final Supplier<Window> owner;
    private final VBox root = new VBox(10);
    private final TextField downloads = new TextField();
    private final TextField instances = new TextField();
    private final TextField sharedMinecraft = new TextField();
    private final TextField runtimes = new TextField();

    StoragePathPane(Path launcherRoot, LauncherSettings settings, Supplier<Window> owner) {
        this.launcherRoot = launcherRoot;
        this.owner = owner;
        AppPaths paths = AppPaths.fromSettings(launcherRoot, settings);
        downloads.setText(paths.downloadsDirectory().toString());
        instances.setText(paths.instancesDirectory().toString());
        sharedMinecraft.setText(paths.sharedMinecraftDirectory().toString());
        runtimes.setText(paths.runtimesDirectory().toString());
        configure();
    }

    Node view() {
        return root;
    }

    AppPaths validateAndPrepare() throws IOException {
        AppPaths paths;
        try {
            paths = new AppPaths(
                    launcherRoot,
                    parse(downloads, "下载目录"),
                    parse(instances, "版本实例目录"),
                    parse(sharedMinecraft, "Minecraft 公共目录"),
                    parse(runtimes, "Java 运行时目录")
            );
        } catch (RuntimeException failure) {
            throw new IOException("路径格式无效：" + failure.getMessage(), failure);
        }
        List<Path> configured = List.of(paths.downloadsDirectory(), paths.instancesDirectory(),
                paths.sharedMinecraftDirectory(), paths.runtimesDirectory());
        if (new HashSet<>(configured).size() != configured.size()) {
            throw new IOException("下载、版本、公共文件和 Java 目录不能设置为同一个文件夹");
        }
        paths.prepareStorage();
        downloads.setText(paths.downloadsDirectory().toString());
        instances.setText(paths.instancesDirectory().toString());
        sharedMinecraft.setText(paths.sharedMinecraftDirectory().toString());
        runtimes.setText(paths.runtimesDirectory().toString());
        return paths;
    }

    LauncherSettings applyTo(LauncherSettings base) {
        return new LauncherSettings(
                base.locale(), base.theme(), base.maxConcurrentDownloads(), base.playIntro(), base.preferMirror(),
                base.activeProfileId(), base.onboardingCompleted(), downloads.getText(), instances.getText(),
                sharedMinecraft.getText(), runtimes.getText()
        );
    }

    private void configure() {
        Label copy = new Label("大型文件可放到 D 盘或其他磁盘。修改后需重启启动器；不会自动移动旧目录中的实例。");
        copy.getStyleClass().add("page-subtitle");
        copy.setWrapText(true);
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(9);
        addRow(grid, 0, "下载与缓存", downloads, "整合包、加载器工具等下载文件");
        addRow(grid, 1, "版本实例", instances, "每个游戏版本的 mods、存档、日志和配置");
        addRow(grid, 2, "公共游戏文件", sharedMinecraft, "跨实例共享的 versions、libraries 和 assets");
        addRow(grid, 3, "Java 运行时", runtimes, "为不同 Minecraft 版本自动下载的 Java");
        root.setPadding(new Insets(4, 0, 4, 0));
        root.getChildren().addAll(copy, grid);
        I18n.localize(root);
    }

    private void addRow(GridPane grid, int row, String title, TextField field, String hint) {
        field.setPromptText("请选择文件夹");
        field.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(field, Priority.ALWAYS);
        Button choose = new Button("浏览…");
        choose.getStyleClass().add("ghost-button");
        choose.setOnAction(event -> chooseDirectory(title, field));
        HBox picker = new HBox(8, field, choose);
        picker.setAlignment(Pos.CENTER_LEFT);
        GridPane.setHgrow(picker, Priority.ALWAYS);
        Label name = new Label(title);
        name.getStyleClass().add("setting-label");
        Label detail = new Label(hint);
        detail.getStyleClass().add("version-date");
        grid.add(name, 0, row * 2);
        grid.add(picker, 1, row * 2);
        grid.add(detail, 1, row * 2 + 1);
    }

    private void chooseDirectory(String title, TextField field) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择" + title);
        try {
            File current = Path.of(field.getText().trim()).toFile();
            if (current.isDirectory()) chooser.setInitialDirectory(current);
        } catch (RuntimeException ignored) {
            // Let the chooser use the operating system's last directory.
        }
        File selected = chooser.showDialog(owner.get());
        if (selected != null) field.setText(selected.toPath().toAbsolutePath().normalize().toString());
    }

    private Path parse(TextField field, String name) throws IOException {
        String value = field.getText() == null ? "" : field.getText().trim();
        if (value.isBlank()) throw new IOException(name + "不能为空");
        return Path.of(value).toAbsolutePath().normalize();
    }
}
