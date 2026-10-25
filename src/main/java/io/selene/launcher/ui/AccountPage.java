package io.selene.launcher.ui;

import io.selene.launcher.application.AccountService;
import io.selene.launcher.core.model.AccountProfile;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;

/** Account center for offline, Microsoft device-code and custom Yggdrasil profiles. */
public final class AccountPage {
    private final AccountService accounts;
    private final Consumer<String> errorHandler;
    private final Runnable accountChanged;
    private final VBox root = new VBox(15);
    private final Label active = new Label();
    private final Label status = new Label("请选择登录方式");

    public AccountPage(AccountService accounts, Consumer<String> errorHandler, Runnable accountChanged) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.accountChanged = Objects.requireNonNull(accountChanged, "accountChanged");
        configure();
    }

    public Node view() {
        refreshActive();
        return root;
    }

    private void configure() {
        root.getStyleClass().add("page");
        Label eyebrow = new Label("IDENTITY & SESSION");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("账号中心");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("支持离线档案、Microsoft 正版设备码登录，以及 LittleSkin 等标准 Yggdrasil 外置登录服务。");
        subtitle.getStyleClass().add("page-subtitle");
        VBox heading = new VBox(3, eyebrow, title, subtitle);

        active.getStyleClass().add("settings-title");
        status.getStyleClass().add("save-status");
        VBox summary = new VBox(7, active, status);
        summary.getStyleClass().add("catalog-summary");
        summary.setPadding(new Insets(14, 18, 14, 18));

        TabPane tabs = new TabPane(offlineTab(), microsoftTab(), yggdrasilTab());
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().addAll(heading, summary, tabs);
        I18n.localize(root);
    }

    private Tab offlineTab() {
        TextField name = new TextField(accounts.active().type() == AccountProfile.Type.OFFLINE
                ? accounts.active().displayName() : "Traveler");
        name.setPromptText("3–16 位英文、数字或下划线");
        Button use = new Button("使用离线档案");
        use.getStyleClass().add("primary-button");
        use.setOnAction(event -> {
            try {
                accounts.useOffline(name.getText());
                status.setText("离线档案已启用");
                refreshActive();
                accountChanged.run();
            } catch (Exception failure) {
                errorHandler.accept(failure.getMessage());
            }
        });
        VBox panel = panel(
                "离线档案不会向 Mojang 验证所有权，适合单人游戏和关闭正版验证的服务器。",
                row("档案名", name), use
        );
        return new Tab("离线档案", panel);
    }

    private Tab microsoftTab() {
        TextField clientId = new TextField();
        clientId.setPromptText("Microsoft Entra 应用客户端 ID");
        Label code = new Label("尚未申请设备码");
        code.getStyleClass().add("settings-title");
        Button login = new Button("获取设备码并登录");
        login.getStyleClass().add("primary-button");
        login.setOnAction(event -> {
            login.setDisable(true);
            status.setText("正在申请 Microsoft 设备码…");
            accounts.requestMicrosoftDeviceCode(clientId.getText()).whenComplete((device, failure) -> Platform.runLater(() -> {
                if (failure != null) {
                    login.setDisable(false);
                    errorHandler.accept("Microsoft 登录失败：" + rootCause(failure));
                    return;
                }
                code.setText("验证码：" + device.userCode() + "    地址：" + device.verificationUri());
                status.setText("请在浏览器完成授权，Selene Launcher 正在等待…");
                openBrowser(device.verificationUri());
                accounts.finishMicrosoftLogin(device, text -> Platform.runLater(() -> status.setText(text)))
                        .whenComplete((profile, loginFailure) -> Platform.runLater(() -> {
                            login.setDisable(false);
                            if (loginFailure != null) {
                                errorHandler.accept("Microsoft 登录失败：" + rootCause(loginFailure));
                            } else {
                                refreshActive();
                                accountChanged.run();
                            }
                        }));
            }));
        });
        VBox panel = panel(
                "正版登录使用 Microsoft OAuth 设备码流程。你需要为自己的发行版申请允许设备码流程的客户端 ID；Selene Launcher 不会借用其他启动器的身份。",
                row("客户端 ID", clientId), code, login
        );
        return new Tab("Microsoft 正版", panel);
    }

    private Tab yggdrasilTab() {
        TextField api = new TextField("https://littleskin.cn/api/yggdrasil/");
        TextField username = new TextField();
        PasswordField password = new PasswordField();
        username.setPromptText("邮箱或用户名");
        password.setPromptText("密码");
        Button login = new Button("登录外置服务器");
        login.getStyleClass().add("primary-button");
        login.setOnAction(event -> {
            login.setDisable(true);
            accounts.loginYggdrasil(URI.create(api.getText().trim()), username.getText(), password.getText(),
                            text -> Platform.runLater(() -> status.setText(text)))
                    .whenComplete((profile, failure) -> Platform.runLater(() -> {
                        password.clear();
                        login.setDisable(false);
                        if (failure != null) errorHandler.accept("外置登录失败：" + rootCause(failure));
                        else {
                            refreshActive();
                            accountChanged.run();
                        }
                    }));
        });
        VBox panel = panel(
                "预置 LittleSkin，也可填写其他兼容标准 Yggdrasil API 的 HTTPS 地址。账号密码仅用于本次认证，不会写入配置。",
                row("API 地址", api), row("账号", username), row("密码", password), login
        );
        return new Tab("LittleSkin / 外置", panel);
    }

    private VBox panel(Object... children) {
        VBox box = new VBox(13);
        box.getStyleClass().add("settings-panel");
        box.setPadding(new Insets(20));
        for (Object child : children) {
            if (child instanceof String text) {
                Label copy = new Label(text);
                copy.getStyleClass().add("page-subtitle");
                copy.setWrapText(true);
                box.getChildren().add(copy);
            } else if (child instanceof Node node) {
                box.getChildren().add(node);
            }
        }
        return box;
    }

    private HBox row(String name, Node control) {
        Label label = new Label(name);
        label.getStyleClass().add("setting-label");
        HBox row = new HBox(14, label, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void refreshActive() {
        AccountProfile account = accounts.active();
        active.setText(I18n.text("当前账号：") + account.displayName() + " · " + I18n.text(account.type().displayName()));
    }

    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
            status.setText("请手动打开：" + url);
        }
    }

    private String rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
