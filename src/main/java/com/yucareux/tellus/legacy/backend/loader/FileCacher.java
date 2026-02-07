package com.yucareux.tellus.legacy.backend.loader;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public class FileCacher implements Cacher<Path, byte[]> {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Executor executor;

    private final Map<Path, CompletableFuture<Optional<byte[]>>> pendingLoads = new ConcurrentHashMap<>();

    public FileCacher(final Executor executor) {
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getOrLoad(final Path path,
            final Supplier<CompletableFuture<Optional<byte[]>>> loader) {
        return pendingLoads.computeIfAbsent(path, p -> _getOrLoad(p, loader));
    }

    private CompletableFuture<Optional<byte[]>> _getOrLoad(final Path path,
            final Supplier<CompletableFuture<Optional<byte[]>>> loader) {
        return CompletableFuture.supplyAsync(() -> get(path), executor).thenComposeAsync(cachedValue -> {
            if (cachedValue.isPresent()) {
                pendingLoads.remove(path);
                return CompletableFuture.completedFuture(cachedValue);
            }
            final CompletableFuture<Optional<byte[]>> loadFuture = loader.get();
            loadFuture.whenCompleteAsync((loadedValue, throwable) -> {
                if (throwable == null) {
                    put(path, loadedValue);
                }
                pendingLoads.remove(path);
            }, executor);
            return loadFuture;
        }, executor);
    }

    private void put(final Path path, final Optional<byte[]> value) {
        if (value.isEmpty()) {
            deleteQuietly(path);
            return;
        }

        try {
            Files.createDirectories(path.getParent());
            writeSafe(path, value.get());
        } catch (final IOException e) {
            LOGGER.error("Failed to put data tile at {} into cache, dropping", path, e);
            deleteQuietly(path);
        }
    }

    private static void writeSafe(final Path path, final byte[] value) throws IOException {
        final Path temporaryPath = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try {
            Files.write(temporaryPath, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temporaryPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final FileAlreadyExistsException ignored) {
            // Shouldn't happen unless we have multiple instances running, but we should
            // still get to a correct state
        }
    }

    private Optional<byte[]> get(final Path path) {
        try {
            if (Files.exists(path)) {
                return Optional.of(Files.readAllBytes(path));
            }
        } catch (final IOException e) {
            LOGGER.error("Failed to read data tile at {} from cache, dropping", path, e);
            deleteQuietly(path);
        }
        return Optional.empty();
    }

    private static void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ignored) {
        }
    }
}
