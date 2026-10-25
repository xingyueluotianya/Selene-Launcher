package io.selene.launcher.infra;

import io.selene.launcher.core.port.DownloadService;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * HTTP download implementation with resumable part files, source failover and
 * integrity verification. Production instances accept HTTPS sources only.
 */
public final class HttpDownloadService implements DownloadService, AutoCloseable {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient client;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final Set<String> allowedSchemes;

    public HttpDownloadService(int maxConcurrentDownloads) {
        if (maxConcurrentDownloads < 1) {
            throw new IllegalArgumentException("maxConcurrentDownloads must be positive");
        }
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrentDownloads, downloadThreadFactory());
        this.executor = pool;
        this.ownedExecutor = pool;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.allowedSchemes = Set.of("https");
    }

    HttpDownloadService(HttpClient client, Executor executor, Set<String> allowedSchemes) {
        this.client = Objects.requireNonNull(client, "client");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = null;
        this.allowedSchemes = Set.copyOf(allowedSchemes);
        if (this.allowedSchemes.isEmpty()) {
            throw new IllegalArgumentException("At least one URI scheme must be allowed");
        }
    }

    @Override
    public CompletableFuture<Path> download(DownloadRequest request, Consumer<DownloadProgress> progressListener) {
        return download(request, progressListener, DownloadControl.NONE);
    }

    @Override
    public CompletableFuture<Path> download(
            DownloadRequest request,
            Consumer<DownloadProgress> progressListener,
            DownloadControl control
    ) {
        Objects.requireNonNull(request, "request");
        Consumer<DownloadProgress> listener = progressListener == null ? ignored -> { } : progressListener;
        DownloadControl effectiveControl = control == null ? DownloadControl.NONE : control;
        validate(request);
        publish(listener, partSize(request.destination()), request.expectedSize(), DownloadProgress.Status.QUEUED);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return downloadBlocking(request, listener, effectiveControl);
            } catch (DownloadPausedException exception) {
                publish(listener, partSize(request.destination()), request.expectedSize(), DownloadProgress.Status.PAUSED);
                throw exception;
            } catch (DownloadCancelledException exception) {
                deletePart(request.destination());
                publish(listener, 0, request.expectedSize(), DownloadProgress.Status.CANCELLED);
                throw exception;
            } catch (IOException exception) {
                publish(listener, partSize(request.destination()), request.expectedSize(), DownloadProgress.Status.FAILED);
                throw new DownloadFailedException("All download sources failed for " + request.destination(), exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                publish(listener, partSize(request.destination()), request.expectedSize(), DownloadProgress.Status.FAILED);
                throw new DownloadFailedException("Download was interrupted for " + request.destination(), exception);
            }
        }, executor);
    }

    private Path downloadBlocking(
            DownloadRequest request,
            Consumer<DownloadProgress> listener,
            DownloadControl control
    ) throws IOException, InterruptedException {
        Path destination = request.destination().toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("Download destination must have a parent directory");
        }
        Files.createDirectories(parent);

        if (Files.isRegularFile(destination) && verify(destination, request)) {
            long size = Files.size(destination);
            publish(listener, size, totalBytes(request, size), DownloadProgress.Status.COMPLETED);
            return destination;
        }

        Path part = partPath(destination);
        List<IOException> failures = new ArrayList<>();
        for (URI candidate : request.candidates()) {
            checkControl(control);
            try {
                downloadCandidate(candidate, part, request, listener, control);
                publish(listener, Files.size(part), request.expectedSize(), DownloadProgress.Status.VERIFYING);
                verifyOrThrow(part, request);
                moveCompleted(part, destination);
                long size = Files.size(destination);
                publish(listener, size, totalBytes(request, size), DownloadProgress.Status.COMPLETED);
                return destination;
            } catch (IOException exception) {
                failures.add(new IOException("Source failed: " + candidate + " (" + exception.getMessage() + ")", exception));
                if (isCompletedButInvalid(part, request)) {
                    Files.deleteIfExists(part);
                }
            }
        }

        IOException combined = new IOException("No candidate source produced a valid file");
        failures.forEach(combined::addSuppressed);
        throw combined;
    }

    private void downloadCandidate(
            URI candidate,
            Path part,
            DownloadRequest request,
            Consumer<DownloadProgress> listener,
            DownloadControl control
    ) throws IOException, InterruptedException {
        long existing = Files.isRegularFile(part) ? Files.size(part) : 0;
        if (request.expectedSize() >= 0 && existing > request.expectedSize()) {
            Files.delete(part);
            existing = 0;
        }
        if (request.expectedSize() >= 0 && existing == request.expectedSize()) {
            return;
        }

        publish(listener, existing, request.expectedSize(), DownloadProgress.Status.CONNECTING);
        HttpRequest.Builder builder = HttpRequest.newBuilder(candidate)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Selene-Launcher/0.2")
                .GET();
        if (existing > 0) {
            builder.header("Range", "bytes=" + existing + "-");
        }

        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status == 416 && request.expectedSize() >= 0 && existing == request.expectedSize()) {
            response.body().close();
            return;
        }
        if (status != 200 && status != 206) {
            response.body().close();
            throw new IOException("HTTP " + status);
        }

        boolean append = status == 206 && existing > 0;
        if (status == 206) {
            validateContentRange(response, append ? existing : 0);
        }
        if (!append) {
            existing = 0;
        }

        long responseLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        long reportedTotal = request.expectedSize() >= 0
                ? request.expectedSize()
                : responseLength < 0 ? -1 : existing + responseLength;
        StandardOpenOption[] options = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};

        long downloaded = existing;
        try (InputStream input = response.body(); var output = Files.newOutputStream(part, options)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            publish(listener, downloaded, reportedTotal, DownloadProgress.Status.DOWNLOADING);
            while ((read = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Download interrupted");
                }
                checkControl(control);
                output.write(buffer, 0, read);
                downloaded += read;
                if (request.expectedSize() >= 0 && downloaded > request.expectedSize()) {
                    throw new IOException("Downloaded file exceeds expected size");
                }
                publish(listener, downloaded, reportedTotal, DownloadProgress.Status.DOWNLOADING);
            }
        }

        if (responseLength >= 0 && downloaded - existing != responseLength) {
            throw new IOException("Response ended before Content-Length was reached");
        }
    }

    private void validate(DownloadRequest request) {
        if (request.destination() == null) {
            throw new IllegalArgumentException("Download destination is required");
        }
        if (request.expectedSize() < -1) {
            throw new IllegalArgumentException("Expected size must be -1 or non-negative");
        }
        String checksum = normalizedHash(request.checksum());
        if (request.checksumAlgorithm() == ChecksumAlgorithm.NONE && !checksum.isEmpty()) {
            throw new IllegalArgumentException("Checksum algorithm is required when a checksum is supplied");
        }
        if (request.checksumAlgorithm() != ChecksumAlgorithm.NONE) {
            int expectedLength = request.checksumAlgorithm().hexadecimalLength();
            try {
                if (checksum.length() != expectedLength || HexFormat.of().parseHex(checksum).length * 2 != expectedLength) {
                    throw new IllegalArgumentException("Invalid checksum length");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        request.checksumAlgorithm().jcaName() + " checksum must contain " + expectedLength
                                + " hexadecimal characters",
                        exception
                );
            }
        }
        for (URI candidate : request.candidates()) {
            if (candidate == null || candidate.getScheme() == null
                    || !allowedSchemes.contains(candidate.getScheme().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Unsupported download URI: " + candidate);
            }
        }
    }

    private void validateContentRange(HttpResponse<?> response, long expectedStart) throws IOException {
        String contentRange = response.headers().firstValue("Content-Range")
                .orElseThrow(() -> new IOException("Partial response has no Content-Range"));
        String expectedPrefix = "bytes " + expectedStart + "-";
        if (!contentRange.toLowerCase(Locale.ROOT).startsWith(expectedPrefix)) {
            throw new IOException("Unexpected Content-Range: " + contentRange);
        }
    }

    private boolean verify(Path file, DownloadRequest request) throws IOException {
        if (request.expectedSize() >= 0 && Files.size(file) != request.expectedSize()) {
            return false;
        }
        String expectedHash = normalizedHash(request.checksum());
        return expectedHash.isEmpty() || Hashing.digest(file, request.checksumAlgorithm().jcaName())
                .equalsIgnoreCase(expectedHash);
    }

    private void verifyOrThrow(Path file, DownloadRequest request) throws IOException {
        if (request.expectedSize() >= 0 && Files.size(file) != request.expectedSize()) {
            throw new IOException("Size mismatch: expected " + request.expectedSize() + ", got " + Files.size(file));
        }
        String expectedHash = normalizedHash(request.checksum());
        if (!expectedHash.isEmpty()) {
            String actual = Hashing.digest(file, request.checksumAlgorithm().jcaName());
            if (!actual.equalsIgnoreCase(expectedHash)) {
                throw new IOException(request.checksumAlgorithm().jcaName() + " mismatch: expected "
                        + expectedHash + ", got " + actual);
            }
        }
    }

    private boolean isCompletedButInvalid(Path part, DownloadRequest request) throws IOException {
        if (!Files.isRegularFile(part)) {
            return false;
        }
        return request.expectedSize() < 0 || Files.size(part) >= request.expectedSize();
    }

    private void checkControl(DownloadControl control) {
        if (control.cancelRequested()) {
            throw new DownloadCancelledException("Download was cancelled");
        }
        if (control.pauseRequested()) {
            throw new DownloadPausedException("Download was paused");
        }
    }

    private void deletePart(Path destination) {
        try {
            Files.deleteIfExists(partPath(destination.toAbsolutePath().normalize()));
        } catch (IOException ignored) {
            // Cancellation is still terminal; stale temporary data may be cleaned on the next enqueue.
        }
    }

    private void moveCompleted(Path part, Path destination) throws IOException {
        try {
            Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private long partSize(Path destination) {
        try {
            Path part = partPath(destination.toAbsolutePath().normalize());
            return Files.isRegularFile(part) ? Files.size(part) : 0;
        } catch (IOException exception) {
            return 0;
        }
    }

    private Path partPath(Path destination) {
        return destination.resolveSibling(destination.getFileName() + ".part");
    }

    private long totalBytes(DownloadRequest request, long actualSize) {
        return request.expectedSize() >= 0 ? request.expectedSize() : actualSize;
    }

    private String normalizedHash(String sha256) {
        return sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
    }

    private void publish(Consumer<DownloadProgress> listener, long downloaded, long total, DownloadProgress.Status status) {
        try {
            listener.accept(new DownloadProgress(downloaded, total, status));
        } catch (RuntimeException ignored) {
            // A UI observer must not be able to corrupt the download task.
        }
    }

    private static ThreadFactory downloadThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "selene-download-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    public static final class DownloadFailedException extends RuntimeException {
        public DownloadFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class DownloadPausedException extends RuntimeException {
        public DownloadPausedException(String message) {
            super(message);
        }
    }

    public static final class DownloadCancelledException extends RuntimeException {
        public DownloadCancelledException(String message) {
            super(message);
        }
    }
}
