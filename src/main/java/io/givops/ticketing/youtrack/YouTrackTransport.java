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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class YouTrackTransport implements AutoCloseable {
    private final HttpClient client;
    private final ConnectionClients clients;
    private final long maxResponseBytes;
    private final Clock clock;

    YouTrackTransport(HttpClient client, long maxResponseBytes, Clock clock) {
        this(client, maxResponseBytes, clock, ConnectionClients.SYSTEM);
    }

    YouTrackTransport(HttpClient client, long maxResponseBytes, Clock clock, ConnectionClients clients) {
        this.clients = clients;
        this.client = client;
        this.maxResponseBytes = maxResponseBytes;
        this.clock = clock;
    }

    ConnectionClients connectionClients() { return clients; }

    static HttpClient.Builder clientBuilder(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER);
    }

    sealed interface QueryResponse permits QueryResponse.Ok, QueryResponse.Rejected {
        record Ok(byte[] body) implements QueryResponse { }

        record Rejected() implements QueryResponse { }
    }

    private enum BodyMode {
        STRICT,
        ALLOW_BAD_REQUEST,
        ALLOW_NOT_FOUND
    }

    byte[] read(URI uri, String authorization, TicketRequestGate gate) {
        return read(uri, authorization, gate, maxResponseBytes);
    }

    byte[] read(URI uri, String authorization, TicketRequestGate gate, long responseLimit) {
        HttpResponse<byte[]> response = readWithRetry(uri, authorization, gate, responseLimit, BodyMode.STRICT);
        if (response.statusCode() != 200) {
            throw remoteFailure(response.statusCode(), retryAt(response), response.headers().firstValue("Content-Type").orElse(null));
        }
        return response.body();
    }

    QueryResponse readQuery(URI uri, String authorization, TicketRequestGate gate) {
        HttpResponse<byte[]> response = readWithRetry(uri, authorization, gate, maxResponseBytes,
                BodyMode.ALLOW_BAD_REQUEST);
        if (response.statusCode() == 200) {
            return new QueryResponse.Ok(response.body());
        }
        if (response.statusCode() == 400) {
            return new QueryResponse.Rejected();
        }
        throw remoteFailure(response.statusCode(), retryAt(response), response.headers().firstValue("Content-Type").orElse(null));
    }

    Optional<byte[]> readOptional(URI uri, String authorization, TicketRequestGate gate) {
        HttpResponse<byte[]> response = readWithRetry(uri, authorization, gate, maxResponseBytes,
                BodyMode.ALLOW_NOT_FOUND);
        if (response.statusCode() == 200) {
            return Optional.of(response.body());
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        throw remoteFailure(response.statusCode(), retryAt(response), response.headers().firstValue("Content-Type").orElse(null));
    }

    private HttpResponse<byte[]> readWithRetry(URI uri, String authorization, TicketRequestGate gate,
                                               long responseLimit, BodyMode mode) {
        var request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("Authorization", authorization)
                .GET();

        HttpResponse<byte[]> response = exchange(request, gate, responseLimit, mode);
        Instant retryAt = retryAt(response);
        if (retryAt != null) {
            Duration delay = Duration.between(clock.instant(), retryAt);
            if (delay.compareTo(gate.remaining()) < 0) {
                pause(delay);
                response = exchange(request, gate, responseLimit, mode);
            }
        }
        return response;
    }

    private HttpResponse<byte[]> exchange(HttpRequest.Builder request, TicketRequestGate gate, long responseLimit,
                                          BodyMode mode) {
        Duration timeout = gate.beforeRequest();
        long started = System.nanoTime();
        var built = request.timeout(timeout).build();
        var pending = clients.select(gate.connectionTarget(), built.uri(), client).sendAsync(built,
                info -> new LimitedBody(gate, responseLimit, info.statusCode(), mode));
        try {
            long remaining = Math.max(0, timeout.toNanos() - (System.nanoTime() - started));
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            gate.check();
            if (System.nanoTime() - started >= timeout.toNanos()) {
                throw timeout();
            }
            rejectHtml(response);
            return response;
        } catch (TimeoutException exception) {
            pending.cancel(true);
            gate.check();
            throw timeout();
        } catch (InterruptedException exception) {
            pending.cancel(true);
            throw interrupted();
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
            throw new TicketException(Code.PROVIDER_ERROR, YouTrackMessages.format("ticketing.youTrackTransport.the.youtrack.request.could.not.be.completed")).withTransportFailure(cause);
        }
    }

    private Instant retryAt(HttpResponse<byte[]> response) {
        if (response.statusCode() != 429 && response.statusCode() != 503) {
            return null;
        }
        String header = response.headers().firstValue("Retry-After").orElse("").strip();
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

    private static void pause(Duration delay) {
        if (delay.isPositive()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException exception) {
                throw interrupted();
            }
        }
    }

    private static TicketException remoteFailure(int status, Instant retryAt) {
        return remoteFailure(status, retryAt, null);
    }

    private static TicketException remoteFailure(int status, Instant retryAt, String contentType) {
        TicketException result = switch (status) {
            case 401 -> new TicketException(Code.AUTHENTICATION_FAILED, YouTrackMessages.format("ticketing.youTrackTransport.youtrack.rejected.the.configured.credentials"));
            case 403, 404 -> new TicketException(Code.AUTHORIZATION_FAILED,
                    YouTrackMessages.format("ticketing.youTrackTransport.youtrack.did.not.expose.the.requested.resource"));
            case 408 -> timeout();
            case 429 -> new TicketException(Code.RATE_LIMITED, YouTrackMessages.format("ticketing.youTrackTransport.youtrack.rate.limited.the.request"), retryAt);
            case 503 -> new TicketException(Code.PROVIDER_ERROR, YouTrackMessages.format("ticketing.youTrackTransport.youtrack.is.temporarily.unavailable"), retryAt);
            default -> new TicketException(Code.PROVIDER_ERROR, status >= 300 && status < 400
                    ? YouTrackMessages.format("ticketing.youTrackTransport.youtrack.returned.a.redirect") : YouTrackMessages.format("ticketing.youTrackTransport.youtrack.rejected.the.api.request"));
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
        return new TicketException(Code.TIMEOUT, YouTrackMessages.format("ticketing.youTrackTransport.the.youtrack.request.timed.out")).withTransportFailure(new HttpTimeoutException(""));
    }

    private static TicketException interrupted() {
        Thread.currentThread().interrupt();
        return new TicketException(Code.PROVIDER_ERROR, YouTrackMessages.format("ticketing.youTrackTransport.the.youtrack.request.was.interrupted"));
    }

    static TicketException limitExceeded() {
        return new TicketException(Code.LIMIT_EXCEEDED, YouTrackMessages.format("ticketing.youTrackTransport.the.youtrack.read.exceeded.its.resource.budget"));
    }

    @Override
    public void close() {
        client.close();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final TicketRequestGate gate;
        private final long limit;
        private final int status;
        private final BodyMode mode;
        private final ByteArrayOutputStream bytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long count;

        private LimitedBody(TicketRequestGate gate, long limit, int status, BodyMode mode) {
            this.gate = gate;
            this.limit = limit;
            this.status = status;
            this.mode = mode;
            this.bytes = status == 200 ? new ByteArrayOutputStream() : null;
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
                        if (status == 401 || status == 403
                                || (status == 404 && mode != BodyMode.ALLOW_NOT_FOUND)) {
                            throw remoteFailure(status, null);
                        }
                        throw limitExceeded();
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
