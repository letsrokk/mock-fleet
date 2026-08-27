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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;

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

    private static BodyCodec<Buffer> limitedBuffer(int maxPayloadBytes) {
        return handler -> handler.handle(Future.succeededFuture(new LimitedBufferStream(maxPayloadBytes)));
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
