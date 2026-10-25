package io.selene.launcher.core.port;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface DownloadService {
    CompletableFuture<Path> download(DownloadRequest request, Consumer<DownloadProgress> progressListener);

    default CompletableFuture<Path> download(
            DownloadRequest request,
            Consumer<DownloadProgress> progressListener,
            DownloadControl control
    ) {
        return download(request, progressListener);
    }

    record DownloadRequest(
            List<URI> candidates,
            Path destination,
            String checksum,
            ChecksumAlgorithm checksumAlgorithm,
            long expectedSize
    ) {
        public DownloadRequest {
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("At least one download candidate is required");
            }
            checksum = checksum == null ? "" : checksum.trim().toLowerCase(Locale.ROOT);
            checksumAlgorithm = checksumAlgorithm == null ? ChecksumAlgorithm.NONE : checksumAlgorithm;
        }

        /** Compatibility constructor for Selene alpha.3 callers that supplied SHA-256. */
        public DownloadRequest(List<URI> candidates, Path destination, String sha256, long expectedSize) {
            this(candidates, destination, sha256,
                    sha256 == null || sha256.isBlank() ? ChecksumAlgorithm.NONE : ChecksumAlgorithm.SHA_256,
                    expectedSize);
        }
    }

    enum ChecksumAlgorithm {
        NONE("", 0),
        SHA_1("SHA-1", 40),
        SHA_256("SHA-256", 64);

        private final String jcaName;
        private final int hexadecimalLength;

        ChecksumAlgorithm(String jcaName, int hexadecimalLength) {
            this.jcaName = jcaName;
            this.hexadecimalLength = hexadecimalLength;
        }

        public String jcaName() {
            return jcaName;
        }

        public int hexadecimalLength() {
            return hexadecimalLength;
        }
    }

    interface DownloadControl {
        DownloadControl NONE = new DownloadControl() { };

        default boolean pauseRequested() {
            return false;
        }

        default boolean cancelRequested() {
            return false;
        }
    }

    record DownloadProgress(long downloadedBytes, long totalBytes, Status status) {
        public enum Status { QUEUED, CONNECTING, DOWNLOADING, VERIFYING, PAUSED, CANCELLED, COMPLETED, FAILED }
    }
}
