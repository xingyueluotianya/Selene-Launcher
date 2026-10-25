package io.selene.launcher.ui;

import io.selene.launcher.core.model.AppLanguage;
import io.selene.launcher.core.model.LauncherSettings;
import io.selene.launcher.infra.AppPaths;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListCell;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Objects;
import java.util.Optional;

/** A deliberately short first-run step before account and game installation exist. */
public final class FirstRunWizard {
    private FirstRunWizard() {
    }

    public static Optional<LauncherSettings> show(Window owner, LauncherSettings current, AppPaths paths) {
        Dialog<LauncherSettings> dialog = new Dialog<>();
        // The primary stage intentionally has no Scene until onboarding completes. JavaFX 21.0.6
        // dereferences that missing Scene inside HeavyweightDialog when initOwner is called.
        if (shouldInitializeOwner(owner != null, owner != null && owner.getScene() != null)) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(I18n.text("欢迎使用 Selene Launcher"));
        dialog.setHeaderText(I18n.text("先确认基础偏好，之后可随时在设置中修改"));

        ButtonType continueButton = new ButtonType(I18n.text("进入 Selene Launcher"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(continueButton, ButtonType.CANCEL);
        dialog.getDialogPane().getStyleClass().add("first-run-dialog");
        dialog.getDialogPane().getStylesheets().add(Objects.requireNonNull(
                FirstRunWizard.class.getResource("/io/selene/launcher/selene.css")
        ).toExternalForm());

        ComboBox<AppLanguage> language = new ComboBox<>();
        language.getItems().setAll(AppLanguage.values());
        language.getSelectionModel().select(AppLanguage.fromTag(current.locale()));
        language.setCellFactory(ignored -> languageCell());
        language.setButtonCell(languageCell());
        language.valueProperty().addListener((observable, before, selected) -> {
            if (selected != null) {
                I18n.setLanguage(selected);
                dialog.setTitle(I18n.text("欢迎使用 Selene Launcher"));
                dialog.setHeaderText(I18n.text("先确认基础偏好，之后可随时在设置中修改"));
                I18n.localize(dialog.getDialogPane());
                language.setButtonCell(languageCell());
            }
        });

        CheckBox playIntro = new CheckBox("启动时播放开场动效");
        playIntro.setSelected(current.playIntro());
        CheckBox preferMirror = new CheckBox("网络不稳定时优先尝试镜像源");
        preferMirror.setSelected(current.preferMirror());

        Spinner<Integer> concurrency = new Spinner<>();
        concurrency.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 16, current.maxConcurrentDownloads()
        ));
        concurrency.setEditable(true);
        concurrency.setPrefWidth(110);

        GridPane options = new GridPane();
        options.setHgap(14);
        options.setVgap(11);
        options.add(new Label("界面语言"), 0, 0);
        options.add(language, 1, 0);
        options.add(new Label("并发下载数"), 0, 1);
        options.add(concurrency, 1, 1);
        options.add(playIntro, 0, 2, 2, 1);
        options.add(preferMirror, 0, 3, 2, 1);

        Label path = new Label(paths.root().toString());
        path.getStyleClass().add("wizard-path");
        path.setWrapText(true);
        StoragePathPane storage = new StoragePathPane(paths.root(), current, dialog::getOwner);
        Label validation = new Label();
        validation.getStyleClass().add("save-status");
        validation.setWrapText(true);
        VBox content = new VBox(10,
                new Label("启动器配置和日志（体积很小）将保存在："),
                path,
                options,
                new Label("大型文件保存位置"),
                storage.view(),
                validation,
                new Label("Selene Launcher 会自行管理游戏所需 Java；不会修改系统 Java 或环境变量。")
        );
        content.setPadding(new Insets(8, 6, 2, 6));
        content.setPrefWidth(690);
        dialog.getDialogPane().setContent(content);
        I18n.localize(dialog.getDialogPane());
        Button continueNode = (Button) dialog.getDialogPane().lookupButton(continueButton);
        continueNode.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                storage.validateAndPrepare();
                validation.setText("");
            } catch (Exception failure) {
                validation.setText(failure.getMessage());
                event.consume();
            }
        });

        dialog.setResultConverter(button -> {
            if (button != continueButton) return null;
            LauncherSettings result = new LauncherSettings(
                    language.getValue().tag(), current.theme(), concurrency.getValue(), playIntro.isSelected(),
                    preferMirror.isSelected(), current.activeProfileId(), true,
                    current.downloadsDirectory(), current.instancesDirectory(), current.sharedMinecraftDirectory(),
                    current.runtimesDirectory()
            );
            return storage.applyTo(result);
        });
        return dialog.showAndWait();
    }

    static boolean shouldInitializeOwner(boolean ownerPresent, boolean ownerHasScene) {
        return ownerPresent && ownerHasScene;
    }

    private static ListCell<AppLanguage> languageCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(AppLanguage item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.displayName(I18n.language()));
            }
        };
    }
}
