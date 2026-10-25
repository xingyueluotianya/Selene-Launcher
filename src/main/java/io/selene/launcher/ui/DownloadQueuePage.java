package io.selene.launcher.ui;

import io.selene.launcher.application.DownloadQueueService;
import io.selene.launcher.core.model.DownloadTask;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

public final class DownloadQueuePage {
    private final DownloadQueueService queue;
    private final VBox root = new VBox(18);
    private final ObservableList<DownloadTask> items = FXCollections.observableArrayList();
    private final Label summary = new Label("下载队列为空");

    public DownloadQueuePage(DownloadQueueService queue) {
        this.queue = queue;
        configure();
        queue.subscribe(tasks -> Platform.runLater(() -> apply(tasks)));
    }

    public Node view() {
        return root;
    }

    private void configure() {
        root.getStyleClass().add("page");
        Label eyebrow = new Label("TRANSFER CONTROL");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("下载中心");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("安装文件统一进入持久化队列；暂停会保留断点，继续时自动发起 Range 请求。");
        subtitle.getStyleClass().add("page-subtitle");
        VBox heading = new VBox(3, eyebrow, title, subtitle);

        summary.getStyleClass().add("settings-title");
        Button clear = new Button("清理已结束任务");
        clear.getStyleClass().add("ghost-button");
        clear.setOnAction(event -> queue.clearFinished());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, summary, spacer, clear);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        ListView<DownloadTask> list = new ListView<>(items);
        list.setPlaceholder(new Label("还没有下载任务，安装游戏后会在这里显示"));
        list.setCellFactory(ignored -> new DownloadCell());
        VBox.setVgrow(list, Priority.ALWAYS);
        root.getChildren().addAll(heading, toolbar, list);
        I18n.localize(root);
    }

    private void apply(List<DownloadTask> tasks) {
        items.setAll(tasks.reversed());
        long active = tasks.stream().filter(task -> switch (task.status()) {
            case QUEUED, CONNECTING, DOWNLOADING, VERIFYING -> true;
            default -> false;
        }).count();
        long paused = tasks.stream().filter(task -> task.status() == DownloadTask.Status.PAUSED).count();
        summary.setText("共 " + tasks.size() + " 项 · 活动 " + active + " · 暂停 " + paused);
    }

    private final class DownloadCell extends ListCell<DownloadTask> {
        @Override
        protected void updateItem(DownloadTask task, boolean empty) {
            super.updateItem(task, empty);
            if (empty || task == null) {
                setGraphic(null);
                return;
            }
            Label name = new Label(task.name());
            name.getStyleClass().add("version-id");
            Label detail = new Label(I18n.text(task.status().displayName()) + " · " + byteText(task.downloadedBytes(), task.totalBytes())
                    + (task.detail().isBlank() ? "" : " · " + task.detail()));
            detail.getStyleClass().add("page-subtitle");
            detail.setMaxWidth(620);
            VBox text = new VBox(4, name, detail);
            HBox.setHgrow(text, Priority.ALWAYS);

            ProgressBar progress = new ProgressBar(task.totalBytes() > 0
                    ? Math.min(1, (double) task.downloadedBytes() / task.totalBytes()) : ProgressBar.INDETERMINATE_PROGRESS);
            progress.setPrefWidth(170);
            Button pause = new Button(task.status() == DownloadTask.Status.PAUSED || task.status() == DownloadTask.Status.FAILED
                    ? "继续" : "暂停");
            pause.getStyleClass().add("ghost-button");
            pause.setDisable(task.status() == DownloadTask.Status.COMPLETED || task.status() == DownloadTask.Status.CANCELLED);
            pause.setOnAction(event -> {
                if (task.status() == DownloadTask.Status.PAUSED || task.status() == DownloadTask.Status.FAILED) queue.resume(task.id());
                else queue.pause(task.id());
            });
            Button cancel = new Button("取消");
            cancel.getStyleClass().add("danger-button");
            cancel.setDisable(task.status() == DownloadTask.Status.COMPLETED || task.status() == DownloadTask.Status.CANCELLED);
            cancel.setOnAction(event -> queue.cancel(task.id()));
            HBox row = new HBox(12, text, progress, pause, cancel);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(7, 4, 7, 4));
            setGraphic(row);
        }
    }

    private String byteText(long done, long total) {
        if (total <= 0) return human(done);
        return human(done) + " / " + human(total);
    }

    private String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return "%.1f KiB".formatted(bytes / 1024d);
        if (bytes < 1024L * 1024 * 1024) return "%.1f MiB".formatted(bytes / 1024d / 1024d);
        return "%.2f GiB".formatted(bytes / 1024d / 1024d / 1024d);
    }
}
