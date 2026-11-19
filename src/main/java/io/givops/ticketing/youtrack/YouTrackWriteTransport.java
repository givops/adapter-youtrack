package io.givops.ticketing.youtrack;

import io.givops.adapters.ConnectionClients;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.port.TicketRequestGate;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Single-attempt HTTP POST transport for YouTrack writes; uncertain mutations are never replayed. */
final class YouTrackWriteTransport implements AutoCloseable {
    private final HttpClient client;
    private final ConnectionClients clients;
    private final long maxResponseBytes;
    private final Clock clock;

    YouTrackWriteTransport(HttpClient client, long maxResponseBytes, Clock clock) {
        this(client, maxResponseBytes, clock, ConnectionClients.SYSTEM);
    }

    YouTrackWriteTransport(HttpClient client, long maxResponseBytes, Clock clock, ConnectionClients clients) {
        this.clients = clients;
        this.client = client;
        this.maxResponseBytes = maxResponseBytes;
        this.clock = clock;
    }

    byte[] postJson(URI endpoint, String authorization, byte[] body, TicketRequestGate gate) {
        Duration timeout = gate.beforeRequest();
        long started = System.nanoTime();
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        var pending = clients.select(gate.connectionTarget(), request.uri(), client).sendAsync(request, info -> new LimitedWriteBody(gate, maxResponseBytes, info.statusCode()));
        HttpResponse<byte[]> response;
        try {
            long remaining = Math.max(0, timeout.toNanos() - (System.nanoTime() - started));
            response = pending.get(remaining, TimeUnit.NANOSECONDS);
            gate.check();
            if (System.nanoTime() - started >= timeout.toNanos()) {
                throw timeout();
            }
        } catch (TimeoutException exception) {
            pending.cancel(true);
            gate.check();
            throw timeout();
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new TicketException(Code.PROVIDER_ERROR, YouTrackMessages.format("ticketing.youTrackWriteTransport.the.youtrack.write.request.was.interrupted"));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof TicketException failure) {
                throw failure;
            }
            gate.check();
            if (cause instanceof HttpTimeoutException) {
                throw timeout();
            }
            throw new TicketException(Code.PROVIDER_ERROR, YouTrackMessages.format("ticketing.youTrackWriteTransport.the.youtrack.write.request.could.not.be.completed")).withTransportFailure(cause);
        }

        int status = response.statusCode();
        if (status == 200 || status == 201) {
            rejectHtml(response);
            return response.body();
        }
        throw writeFailure(status, retryAt(response), response.headers().firstValue("Content-Type").orElse(null));
    }

    private Instant retryAt(HttpResponse<byte[]> response) {
        if (response.statusCode() != 429 && response.statusCode() != 503) {
            return null;
        }
        String header = response.headers().firstValue("Retry-After").orElse("").strip();
        if (header.isEmpty()) {
            return null;
        }
        Instant now = clock.instant();
        try {
            if (header.matches("[0-9]{1,18}")) {
                return now.plusSeconds(Long.parseLong(header));
            }
            Instant at = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return at.isAfter(now) ? at : now;
        } catch (DateTimeException | ArithmeticException exception) {
            return null;
        }
    }

    private static TicketException writeFailure(int status, Instant retryAt) {
        return writeFailure(status, retryAt, null);
    }

    private static TicketException writeFailure(int status, Instant retryAt, String contentType) {
        TicketException result = switch (status) {
            case 401 -> new TicketException(Code.AUTHENTICATION_FAILED, YouTrackMessages.format("ticketing.youTrackWriteTransport.youtrack.rejected.the.configured.credentials"));
            case 403, 404 -> new TicketException(Code.AUTHORIZATION_FAILED,
                    YouTrackMessages.format("ticketing.youTrackWriteTransport.youtrack.did.not.permit.the.requested.write.operation"));
            case 400, 409, 422 -> new TicketException(Code.INVALID_CONFIGURATION,
                    YouTrackMessages.format("ticketing.youTrackWriteTransport.youtrack.rejected.the.write.payload.or.reported.a.conflicting.identifier"));
            case 408 -> timeout();
            case 429 -> new TicketException(Code.RATE_LIMITED, YouTrackMessages.format("ticketing.youTrackWriteTransport.youtrack.rate.limited.the.write.request"), retryAt);
            case 503 -> new TicketException(Code.PROVIDER_ERROR, YouTrackMessages.format("ticketing.youTrackWriteTransport.youtrack.is.temporarily.unavailable"), retryAt);
            default -> new TicketException(Code.PROVIDER_ERROR, status >= 300 && status < 400
                    ? YouTrackMessages.format("ticketing.youTrackWriteTransport.youtrack.returned.a.redirect") : YouTrackMessages.format("ticketing.youTrackWriteTransport.youtrack.could.not.complete.the.write.request"));
        };
        return result.withHttpResponse(status, contentType);
    }

    private static void rejectHtml(HttpResponse<byte[]> response) {
        String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
        if (response.statusCode() >= 200 && response.statusCode() < 300
                && (type.equalsIgnoreCase("text/html") || type.equalsIgnoreCase("application/xhtml+xml"))) {
            throw YouTrackPayload.invalidResponse().withHttpResponse(response.statusCode(), type);
        }
    }

    private static TicketException timeout() {
        return new TicketException(Code.TIMEOUT, YouTrackMessages.format("ticketing.youTrackWriteTransport.the.youtrack.write.request.timed.out")).withTransportFailure(new HttpTimeoutException(""));
    }

    @Override
    public void close() {
        client.close();
    }

    private static final class LimitedWriteBody implements HttpResponse.BodySubscriber<byte[]> {
        private final TicketRequestGate gate;
        private final long limit;
        private final int status;
        private final ByteArrayOutputStream bytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long count;

        private LimitedWriteBody(TicketRequestGate gate, long limit, int status) {
            this.gate = gate;
            this.limit = limit;
            this.status = status;
            this.bytes = (status == 200 || status == 201) ? new ByteArrayOutputStream() : null;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            // Drain up to the limit rather than cancelling in onSubscribe, which races HTTP/2 streams in HttpClient.
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    int size = buffer.remaining();
                    gate.receivedBytes(size);
                    count += size;
                    if (count > limit) {
                        if (status == 401 || status == 403 || status == 404) {
                            throw writeFailure(status, null);
                        }
                        throw YouTrackTransport.limitExceeded();
                    }
                    if (bytes != null) {
                        var chunk = new byte[size];
                        buffer.get(chunk);
                        bytes.writeBytes(chunk);
                    }
                }
                subscription.request(1);
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes == null ? new byte[0] : bytes.toByteArray());
        }
    }
}
