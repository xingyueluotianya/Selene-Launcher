package io.selene.launcher.core.model;

import io.selene.launcher.core.port.DownloadService;

import java.time.Instant;

public record DownloadTask(
        String id,
        String name,
        DownloadService.DownloadRequest request,
        Status status,
        long downloadedBytes,
        long totalBytes,
        String detail,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status {
        QUEUED("等待中"),
        CONNECTING("正在连接"),
        DOWNLOADING("下载中"),
        VERIFYING("正在校验"),
        PAUSED("已暂停"),
        COMPLETED("已完成"),
        FAILED("失败"),
        CANCELLED("已取消");

        private final String displayName;

        Status(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }
}
