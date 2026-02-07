package com.yucareux.tellus.legacy.backend.loader;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public record HttpLoader(HttpClient client, Duration requestTimeout) implements Loader<URI, byte[]> {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String USER_AGENT = "Terrarium/2.0";

    @Override
    public CompletableFuture<Optional<byte[]>> load(final URI uri) {
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(requestTimeout)
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).handle((response, throwable) -> {
            if (throwable == null) {
                return Optional.of(response.body());
            } else {
                LOGGER.error("Failed to load data from {}", uri, throwable);
                return Optional.empty();
            }
        });
    }
}
