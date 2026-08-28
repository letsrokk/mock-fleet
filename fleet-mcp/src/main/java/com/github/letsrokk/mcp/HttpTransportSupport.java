package com.github.letsrokk.mcp;

import io.vertx.core.MultiMap;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.codec.spi.BodyStream;
import io.vertx.ext.web.client.predicate.ErrorConverter;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import io.vertx.ext.web.client.predicate.ResponsePredicateResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

final class HttpTransportSupport {

    private static final List<String> HOP_BY_HOP_HEADERS = List.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "host", "content-length");

    private HttpTransportSupport() {
    }

    static void applyHeaders(HttpRequest<Buffer> request, Map<String, List<String>> headers) {
        headers.forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                request.putHeader(name, values);
            }
        });
    }

    static TransportResponse await(HttpRequest<Buffer> request, byte[] body, int maxPayloadBytes) {
        try {
            HttpRequest<Buffer> limitedRequest = request.as(limitedBuffer(maxPayloadBytes));
            HttpResponse<Buffer> response = (body == null || body.length == 0
                    ? limitedRequest.send()
                    : limitedRequest.sendBuffer(Buffer.buffer(body)))
                    .toCompletionStage().toCompletableFuture().join();
            byte[] responseBody = response.bodyAsBuffer() == null ? new byte[0] : response.bodyAsBuffer().getBytes();
            if (responseBody.length > maxPayloadBytes) {
                throw new McpOperationException("RESULT_TOO_LARGE", "Response payload exceeds the configured limit", false,
                        Map.of("limitBytes", maxPayloadBytes));
            }
            return new TransportResponse(response.statusCode(), headers(response.headers()), responseBody);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof McpOperationException operationException) {
                throw operationException;
            }
            throw new McpOperationException("UPSTREAM_UNAVAILABLE", "Internal service request failed: " + cause.getMessage(),
                    true, Map.of("cause", cause.getClass().getSimpleName()));
        }
    }

    static CollectionScan awaitCollection(HttpRequest<Buffer> request, byte[] body, ObjectMapper mapper,
            String collectionField, long position, int limit, long maxBytes, int maxItems) {
        AtomicReference<CollectionScan> completedPage = new AtomicReference<>();
        try {
            HttpRequest<CollectionScan> streamingRequest = request.as(streamingCollection(mapper, collectionField,
                    position, limit, maxBytes, maxItems, completedPage));
            streamingRequest.expect(ResponsePredicate.create(response -> {
                int status = response.statusCode();
                return status >= 200 && status < 300
                        ? ResponsePredicateResult.success()
                        : ResponsePredicateResult.failure("WireMock returned HTTP " + status);
            }, ErrorConverter.create(result -> {
                int status = result.response().statusCode();
                return new McpOperationException("WIREMOCK_ADMIN_ERROR", "WireMock returned HTTP " + status,
                        status >= 500, Map.of("status", status));
            })));
            HttpResponse<CollectionScan> response = (body == null || body.length == 0
                    ? streamingRequest.send()
                    : streamingRequest.sendBuffer(Buffer.buffer(body)))
                    .toCompletionStage().toCompletableFuture().join();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new McpOperationException("WIREMOCK_ADMIN_ERROR",
                        "WireMock returned HTTP " + response.statusCode(), response.statusCode() >= 500,
                        Map.of("status", response.statusCode()));
            }
            return response.body();
        } catch (CompletionException e) {
            CollectionScan page = completedPage.get();
            if (page != null) {
                return page;
            }
            Throwable cause = unwrap(e);
            if (cause instanceof McpOperationException operationException) {
                throw operationException;
            }
            throw new McpOperationException("UPSTREAM_UNAVAILABLE",
                    "Internal service request failed: " + cause.getMessage(), true,
                    Map.of("cause", cause.getClass().getSimpleName()));
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static BodyCodec<Buffer> limitedBuffer(int maxPayloadBytes) {
        return handler -> handler.handle(Future.succeededFuture(new LimitedBufferStream(maxPayloadBytes)));
    }

    private static BodyCodec<CollectionScan> streamingCollection(ObjectMapper mapper, String collectionField,
            long position, int limit, long maxBytes, int maxItems, AtomicReference<CollectionScan> completedPage) {
        return handler -> handler.handle(Future.succeededFuture(new StreamingCollectionStream(mapper,
                collectionField, position, limit, maxBytes, maxItems, completedPage)));
    }

    private static final class StreamingCollectionStream implements BodyStream<CollectionScan> {
        private final ObjectMapper mapper;
        private final String collectionField;
        private final long position;
        private final int limit;
        private final long maxBytes;
        private final int maxItems;
        private final AtomicReference<CollectionScan> completedPage;
        private final Buffer content = Buffer.buffer();
        private final Promise<CollectionScan> result = Promise.promise();
        private Handler<Throwable> exceptionHandler;

        private StreamingCollectionStream(ObjectMapper mapper, String collectionField, long position, int limit,
                long maxBytes, int maxItems, AtomicReference<CollectionScan> completedPage) {
            this.mapper = mapper;
            this.collectionField = collectionField;
            this.position = position;
            this.limit = limit;
            this.maxBytes = maxBytes;
            this.maxItems = maxItems;
            this.completedPage = completedPage;
        }

        @Override
        public Future<CollectionScan> result() {
            return result.future();
        }

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            exceptionHandler = handler;
            return this;
        }

        @Override
        public Future<Void> write(Buffer data) {
            if (content.length() + data.length() > maxBytes) {
                return fail(new McpOperationException("RESULT_TOO_LARGE",
                        "Collection scan byte limit exceeded", false,
                        Map.of("limitBytes", maxBytes, "position", position)));
            }
            content.appendBuffer(data);
            try {
                CollectionScan scan = CollectionScanner.scan(mapper, content.getBytes(), collectionField, position,
                        limit, maxBytes, maxItems);
                if (scan.hasMore()) {
                    completedPage.compareAndSet(null, scan);
                    result.tryComplete(scan);
                    return Future.failedFuture(new CollectionPageComplete());
                }
            } catch (McpOperationException failure) {
                if (!"INVALID_UPSTREAM_RESPONSE".equals(failure.code())) {
                    return fail(failure);
                }
            }
            return Future.succeededFuture();
        }

        @Override
        public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
            write(data).onComplete(handler);
        }

        @Override
        public void end(Handler<AsyncResult<Void>> handler) {
            try {
                CollectionScan scan = CollectionScanner.scan(mapper, content.getBytes(), collectionField, position,
                        limit, maxBytes, maxItems);
                result.tryComplete(scan);
                handler.handle(Future.succeededFuture());
            } catch (RuntimeException failure) {
                fail(failure).onComplete(handler);
            }
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return false;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            return this;
        }

        @Override
        public void handle(Throwable failure) {
            fail(failure);
        }

        private Future<Void> fail(Throwable failure) {
            result.tryFail(failure);
            if (exceptionHandler != null) {
                exceptionHandler.handle(failure);
            }
            return Future.failedFuture(failure);
        }
    }

    private static final class CollectionPageComplete extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class LimitedBufferStream implements BodyStream<Buffer> {
        private final int limit;
        private final Buffer content = Buffer.buffer();
        private final Promise<Buffer> result = Promise.promise();
        private Handler<Throwable> exceptionHandler;

        private LimitedBufferStream(int limit) {
            this.limit = limit;
        }

        @Override
        public Future<Buffer> result() {
            return result.future();
        }

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            exceptionHandler = handler;
            return this;
        }

        @Override
        public Future<Void> write(Buffer data) {
            if (content.length() + data.length() > limit) {
                McpOperationException failure = new McpOperationException("RESULT_TOO_LARGE",
                        "Response payload exceeds the configured limit", false, Map.of("limitBytes", limit));
                result.tryFail(failure);
                if (exceptionHandler != null) {
                    exceptionHandler.handle(failure);
                }
                return Future.failedFuture(failure);
            }
            content.appendBuffer(data);
            return Future.succeededFuture();
        }

        @Override
        public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
            write(data).onComplete(handler);
        }

        @Override
        public void end(Handler<AsyncResult<Void>> handler) {
            result.tryComplete(content);
            handler.handle(Future.succeededFuture());
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return false;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            return this;
        }

        @Override
        public void handle(Throwable failure) {
            result.tryFail(failure);
            if (exceptionHandler != null) {
                exceptionHandler.handle(failure);
            }
        }
    }

    private static Map<String, List<String>> headers(MultiMap source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.entries().forEach(entry -> result
                .computeIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                .add(entry.getValue()));
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return Map.copyOf(result);
    }
}
