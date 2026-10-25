package io.selene.launcher.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.selene.launcher.core.model.DownloadTask;
import io.selene.launcher.core.port.DownloadService;
import io.selene.launcher.infra.AppLog;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Persistent, observable download queue with cooperative pause, resume and cancellation. */
public final class DownloadQueueService {
    private static final long PERSIST_GRANULARITY = 1024L * 1024L;

    private final DownloadService downloadService;
    private final Path queueFile;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, DownloadTask> tasks = new LinkedHashMap<>();
    private final Map<String, TaskControl> controls = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<Path>> completions = new LinkedHashMap<>();
    private final Map<String, Integer> automaticRetries = new LinkedHashMap<>();
    private final List<Consumer<List<DownloadTask>>> listeners = new CopyOnWriteArrayList<>();
    private long lastPersistNanos;

    public DownloadQueueService(DownloadService downloadService, Path queueFile) {
        this.downloadService = Objects.requireNonNull(downloadService, "downloadService");
        this.queueFile = Objects.requireNonNull(queueFile, "queueFile");
        load();
        List<String> recovered;
        synchronized (this) {
            recovered = tasks.values().stream()
                    .filter(task -> task.status() == DownloadTask.Status.QUEUED)
                    .map(DownloadTask::id)
                    .toList();
        }
        recovered.forEach(this::start);
    }

    public String enqueue(String name, DownloadService.DownloadRequest request) {
        return enqueueAll(List.of(new EnqueueRequest(name, request))).getFirst();
    }

    public List<String> enqueueAll(List<EnqueueRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(requests.size());
        synchronized (this) {
            for (EnqueueRequest item : requests) {
                DownloadService.DownloadRequest request = Objects.requireNonNull(item.request(), "request");
                DownloadTask existing = findReusable(request);
                if (existing != null) {
                    ids.add(existing.id());
                    boolean inactive = !controls.containsKey(existing.id());
                    boolean requestChanged = !existing.request().equals(request);
                    if (inactive && requestChanged) {
                        replaceRequest(existing, request, DownloadTask.Status.QUEUED,
                                partSize(request.destination()), "下载来源已更新，重新加入安装计划");
                        completions.put(existing.id(), new CompletableFuture<>());
                    } else if (inactive && (existing.status() == DownloadTask.Status.FAILED
                            || existing.status() == DownloadTask.Status.PAUSED)) {
                        replace(existing, DownloadTask.Status.QUEUED, partSize(request.destination()), "重新加入安装计划");
                        CompletableFuture<Path> completion = completions.get(existing.id());
                        if (completion == null || completion.isDone()) {
                            completions.put(existing.id(), new CompletableFuture<>());
                        }
                    }
                    continue;
                }
                String id = UUID.randomUUID().toString();
                Instant now = Instant.now();
                DownloadTask task = new DownloadTask(
                        id,
                        item.name() == null || item.name().isBlank()
                                ? request.destination().getFileName().toString() : item.name(),
                        request,
                        DownloadTask.Status.QUEUED,
                        partSize(request.destination()),
                        request.expectedSize(),
                        "等待调度",
                        now,
                        now
                );
                tasks.put(id, task);
                completions.put(id, new CompletableFuture<>());
                ids.add(id);
            }
            persistQuietly();
        }
        notifyListeners();
        ids.forEach(this::start);
        return List.copyOf(ids);
    }

    private DownloadTask findReusable(DownloadService.DownloadRequest request) {
        Path destination = request.destination().toAbsolutePath().normalize();
        return tasks.values().stream()
                .filter(task -> task.request().destination().toAbsolutePath().normalize().equals(destination))
                .filter(task -> task.status() != DownloadTask.Status.CANCELLED)
                .filter(task -> task.status() != DownloadTask.Status.COMPLETED || Files.isRegularFile(destination))
                .findFirst().orElse(null);
    }

    public synchronized List<DownloadTask> tasks() {
        return List.copyOf(tasks.values());
    }

    public void subscribe(Consumer<List<DownloadTask>> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        listener.accept(tasks());
    }

    public void unsubscribe(Consumer<List<DownloadTask>> listener) {
        listeners.remove(listener);
    }

    public synchronized CompletableFuture<Path> completion(String id) {
        DownloadTask task = requireTask(id);
        if (task.status() == DownloadTask.Status.COMPLETED) {
            return CompletableFuture.completedFuture(task.request().destination().toAbsolutePath().normalize());
        }
        return completions.computeIfAbsent(id, ignored -> new CompletableFuture<>());
    }

    public void pause(String id) {
        synchronized (this) {
            DownloadTask task = requireTask(id);
            if (task.status() == DownloadTask.Status.QUEUED) {
                TaskControl control = controls.get(id);
                if (control != null) {
                    control.pause.set(true);
                }
                replace(task, DownloadTask.Status.PAUSED, task.downloadedBytes(), "已暂停");
                persistQuietly();
            } else if (isActive(task.status())) {
                TaskControl control = controls.get(id);
                if (control != null) {
                    control.pause.set(true);
                }
            } else {
                return;
            }
        }
        notifyListeners();
    }

    public void resume(String id) {
        synchronized (this) {
            DownloadTask task = requireTask(id);
            if (task.status() != DownloadTask.Status.PAUSED && task.status() != DownloadTask.Status.FAILED) {
                return;
            }
            replace(task, DownloadTask.Status.QUEUED, partSize(task.request().destination()), "等待继续");
            CompletableFuture<Path> existing = completions.get(id);
            if (existing == null || existing.isCompletedExceptionally() || existing.isCancelled()) {
                completions.put(id, new CompletableFuture<>());
            }
            persistQuietly();
        }
        notifyListeners();
        start(id);
    }

    public void cancel(String id) {
        synchronized (this) {
            DownloadTask task = requireTask(id);
            if (isTerminal(task.status())) {
                return;
            }
            TaskControl control = controls.get(id);
            if (control != null) {
                control.cancel.set(true);
            } else {
                replace(task, DownloadTask.Status.CANCELLED, 0, "已取消");
                completeExceptionally(id, new IllegalStateException("下载已取消"));
                persistQuietly();
            }
        }
        notifyListeners();
    }

    public synchronized void clearFinished() {
        tasks.values().removeIf(task -> isTerminal(task.status()));
        completions.keySet().retainAll(tasks.keySet());
        persistQuietly();
        notifyListeners();
    }

    private void start(String id) {
        DownloadTask task;
        TaskControl control = new TaskControl();
        synchronized (this) {
            task = tasks.get(id);
            if (task == null || task.status() != DownloadTask.Status.QUEUED || controls.containsKey(id)) {
                return;
            }
            controls.put(id, control);
        }
        downloadService.download(task.request(), progress -> onProgress(id, progress), control)
                .whenComplete((path, failure) -> onFinished(id, path, failure));
    }

    private void onProgress(String id, DownloadService.DownloadProgress progress) {
        boolean shouldPersist;
        synchronized (this) {
            DownloadTask before = tasks.get(id);
            if (before == null) {
                return;
            }
            if ((before.status() == DownloadTask.Status.PAUSED && progress.status() != DownloadService.DownloadProgress.Status.PAUSED)
                    || (before.status() == DownloadTask.Status.CANCELLED
                    && progress.status() != DownloadService.DownloadProgress.Status.CANCELLED)) {
                return;
            }
            DownloadTask.Status status = switch (progress.status()) {
                case QUEUED -> DownloadTask.Status.QUEUED;
                case CONNECTING -> DownloadTask.Status.CONNECTING;
                case DOWNLOADING -> DownloadTask.Status.DOWNLOADING;
                case VERIFYING -> DownloadTask.Status.VERIFYING;
                case PAUSED -> DownloadTask.Status.PAUSED;
                case CANCELLED -> DownloadTask.Status.CANCELLED;
                case COMPLETED -> DownloadTask.Status.COMPLETED;
                case FAILED -> DownloadTask.Status.FAILED;
            };
            String detail = status.displayName();
            replace(before, status, progress.downloadedBytes(), detail,
                    progress.totalBytes() >= 0 ? progress.totalBytes() : before.totalBytes());
            boolean importantTerminal = status == DownloadTask.Status.PAUSED
                    || status == DownloadTask.Status.CANCELLED
                    || status == DownloadTask.Status.FAILED;
            boolean crossedGranularity = progress.downloadedBytes() / PERSIST_GRANULARITY
                    != before.downloadedBytes() / PERSIST_GRANULARITY;
            shouldPersist = importantTerminal || (crossedGranularity
                    && System.nanoTime() - lastPersistNanos > 1_000_000_000L);
            if (shouldPersist) {
                persistQuietly();
            }
        }
        notifyListeners();
    }

    private void onFinished(String id, Path path, Throwable failure) {
        synchronized (this) {
            controls.remove(id);
            DownloadTask task = tasks.get(id);
            if (task == null) {
                return;
            }
            if (failure == null) {
                replace(task, DownloadTask.Status.COMPLETED, fileSize(path), "下载与校验完成");
                completions.computeIfAbsent(id, ignored -> new CompletableFuture<>()).complete(path);
                AppLog.info("下载完成：" + task.name() + " -> " + path);
            } else if (task.status() == DownloadTask.Status.PAUSED) {
                replace(task, DownloadTask.Status.PAUSED, partSize(task.request().destination()), "已暂停，可继续");
            } else if (task.status() == DownloadTask.Status.CANCELLED) {
                completeExceptionally(id, new IllegalStateException("下载已取消"));
            } else {
                Throwable cause = unwrap(failure);
                replace(task, DownloadTask.Status.FAILED, partSize(task.request().destination()),
                        cause.getMessage() == null ? "下载失败" : cause.getMessage());
                AppLog.warning("下载失败：" + task.name() + " -> " + task.request().destination(), cause);
                completeExceptionally(id, cause);
            }
            if (failure != null || controls.isEmpty() || System.nanoTime() - lastPersistNanos > 1_000_000_000L) {
                persistQuietly();
            }
        }
        notifyListeners();
    }

    private synchronized void replace(DownloadTask task, DownloadTask.Status status, long downloaded, String detail) {
        replace(task, status, downloaded, detail, task.totalBytes());
    }

    private synchronized void replace(
            DownloadTask task,
            DownloadTask.Status status,
            long downloaded,
            String detail,
            long total
    ) {
        tasks.put(task.id(), new DownloadTask(
                task.id(), task.name(), task.request(), status, downloaded, total, detail,
                task.createdAt(), Instant.now()
        ));
    }

    private synchronized void replaceRequest(
            DownloadTask task,
            DownloadService.DownloadRequest request,
            DownloadTask.Status status,
            long downloaded,
            String detail
    ) {
        tasks.put(task.id(), new DownloadTask(
                task.id(), task.name(), request, status, downloaded, request.expectedSize(), detail,
                task.createdAt(), Instant.now()
        ));
    }

    private synchronized DownloadTask requireTask(String id) {
        DownloadTask task = tasks.get(id);
        if (task == null) {
            throw new IllegalArgumentException("Unknown download task: " + id);
        }
        return task;
    }

    private void notifyListeners() {
        List<DownloadTask> snapshot = tasks();
        listeners.forEach(listener -> {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
                // Observers cannot corrupt queue state.
            }
        });
    }

    private void completeExceptionally(String id, Throwable failure) {
        completions.computeIfAbsent(id, ignored -> new CompletableFuture<>()).completeExceptionally(failure);
    }

    private void load() {
        if (Files.notExists(queueFile)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(queueFile.toFile());
            JsonNode taskNodes = root.path("tasks");
            if (!taskNodes.isArray()) {
                return;
            }
            for (JsonNode node : taskNodes) {
                DownloadTask task = parseTask(node);
                if (task.status() == DownloadTask.Status.CONNECTING
                        || task.status() == DownloadTask.Status.DOWNLOADING
                        || task.status() == DownloadTask.Status.VERIFYING) {
                    task = new DownloadTask(
                            task.id(), task.name(), task.request(), DownloadTask.Status.QUEUED,
                            partSize(task.request().destination()), task.totalBytes(), "应用重启，等待恢复",
                            task.createdAt(), Instant.now()
                    );
                }
                tasks.put(task.id(), task);
                completions.put(task.id(), new CompletableFuture<>());
            }
        } catch (Exception exception) {
            AppLog.warning("无法读取下载队列，将使用空队列", exception);
            tasks.clear();
            completions.clear();
        }
    }

    private DownloadTask parseTask(JsonNode node) {
        List<URI> candidates = new ArrayList<>();
        node.path("candidates").forEach(value -> candidates.add(URI.create(value.asText())));
        DownloadService.DownloadRequest request = new DownloadService.DownloadRequest(
                candidates,
                Path.of(node.path("destination").asText()),
                node.path("checksum").asText(""),
                DownloadService.ChecksumAlgorithm.valueOf(node.path("checksumAlgorithm").asText("NONE")),
                node.path("expectedSize").asLong(-1)
        );
        DownloadTask.Status status = DownloadTask.Status.valueOf(node.path("status").asText("QUEUED"));
        return new DownloadTask(
                node.path("id").asText(), node.path("name").asText(), request, status,
                node.path("downloadedBytes").asLong(0), node.path("totalBytes").asLong(-1),
                node.path("detail").asText(""), Instant.parse(node.path("createdAt").asText()),
                Instant.parse(node.path("updatedAt").asText())
        );
    }

    private synchronized void persistQuietly() {
        try {
            Files.createDirectories(queueFile.toAbsolutePath().normalize().getParent());
            ObjectNode root = objectMapper.createObjectNode();
            root.put("schemaVersion", 1);
            ArrayNode taskNodes = root.putArray("tasks");
            for (DownloadTask task : tasks.values()) {
                ObjectNode node = taskNodes.addObject();
                node.put("id", task.id());
                node.put("name", task.name());
                ArrayNode candidates = node.putArray("candidates");
                task.request().candidates().forEach(uri -> candidates.add(uri.toString()));
                node.put("destination", task.request().destination().toString());
                node.put("checksum", task.request().checksum());
                node.put("checksumAlgorithm", task.request().checksumAlgorithm().name());
                node.put("expectedSize", task.request().expectedSize());
                node.put("status", task.status().name());
                node.put("downloadedBytes", task.downloadedBytes());
                node.put("totalBytes", task.totalBytes());
                node.put("detail", task.detail());
                node.put("createdAt", task.createdAt().toString());
                node.put("updatedAt", task.updatedAt().toString());
            }
            Path temporary = queueFile.resolveSibling(queueFile.getFileName() + ".tmp");
            objectMapper.writeValue(temporary.toFile(), root);
            try {
                Files.move(temporary, queueFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, queueFile, StandardCopyOption.REPLACE_EXISTING);
            }
            lastPersistNanos = System.nanoTime();
        } catch (IOException exception) {
            AppLog.warning("无法保存下载队列", exception);
        }
    }

    private long partSize(Path destination) {
        return fileSize(destination.resolveSibling(destination.getFileName() + ".part"));
    }

    private long fileSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (IOException ignored) {
            return 0;
        }
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private boolean isActive(DownloadTask.Status status) {
        return status == DownloadTask.Status.CONNECTING
                || status == DownloadTask.Status.DOWNLOADING
                || status == DownloadTask.Status.VERIFYING;
    }

    private boolean isTerminal(DownloadTask.Status status) {
        return status == DownloadTask.Status.COMPLETED || status == DownloadTask.Status.CANCELLED;
    }

    public record EnqueueRequest(String name, DownloadService.DownloadRequest request) {
    }

    private static final class TaskControl implements DownloadService.DownloadControl {
        private final AtomicBoolean pause = new AtomicBoolean();
        private final AtomicBoolean cancel = new AtomicBoolean();

        @Override
        public boolean pauseRequested() {
            return pause.get();
        }

        @Override
        public boolean cancelRequested() {
            return cancel.get();
        }
    }
}
