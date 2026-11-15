package io.givops.ticketing.youtrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.port.TicketRequestGate;
import io.givops.ticketing.youtrack.YouTrackTransport.QueryResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class YouTrackTransportTest {
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final URI ENDPOINT = URI.create("https://host.example.com/youtrack/api/admin/projects/0-1");
    private static final String AUTH = "Bearer synthetic-youtrack-token";

    @Test
    void configuresNoRedirectClientAndSendsBearerIdentityHeaders() {
        try (HttpClient built = YouTrackTransport.clientBuilder(Duration.ofSeconds(5)).build()) {
            assertThat(built.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        }

        var fixture = new Fixture(new Reply(200, Map.of(), "{\"id\":\"0-1\"}"));
        var transport = new YouTrackTransport(fixture.client, 1024, CLOCK);
        var gate = new Gate(Duration.ofSeconds(120));

        byte[] body = transport.read(ENDPOINT, AUTH, gate);

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"0-1\"}");
        assertThat(fixture.requests).hasSize(1);
        var request = fixture.requests.getFirst();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri()).isEqualTo(ENDPOINT);
        assertThat(request.headers().firstValue("Accept")).contains("application/json");
        assertThat(request.headers().firstValue("Accept-Encoding")).contains("identity");
        assertThat(request.headers().firstValue("Authorization")).contains(AUTH);
        assertThat(gate.bytes).isEqualTo(body.length);
    }

    @Test
    void translatesAuthRedirectAndServerStatusCodes() {
        assertFailure(401, Code.AUTHENTICATION_FAILED);
        assertFailure(403, Code.AUTHORIZATION_FAILED);
        assertFailure(404, Code.AUTHORIZATION_FAILED);
        assertFailure(302, Code.PROVIDER_ERROR);
        assertFailure(500, Code.PROVIDER_ERROR);
    }

    @Test
    void retriesOnRateLimitWithinBudgetAndReportsRetryAtWhenExhausted() {
        var withinBudget = new Fixture(
                new Reply(429, Map.of("Retry-After", List.of("0")), "{}"),
                new Reply(200, Map.of(), "[]"));
        var transport = new YouTrackTransport(withinBudget.client, 1024, CLOCK);
        byte[] ok = transport.read(ENDPOINT, AUTH, new Gate(Duration.ofSeconds(10)));
        assertThat(new String(ok, StandardCharsets.UTF_8)).isEqualTo("[]");
        assertThat(withinBudget.requests).hasSize(2);

        var stillLimited = new Fixture(
                new Reply(429, Map.of("Retry-After", List.of("0")), "{}"),
                new Reply(429, Map.of("Retry-After", List.of("5")), "{}"));
        assertThatExceptionOfType(TicketException.class)
                .isThrownBy(() -> new YouTrackTransport(stillLimited.client, 1024, CLOCK)
                        .read(ENDPOINT, AUTH, new Gate(Duration.ofSeconds(10))))
                .satisfies(ex -> {
                    assertThat(ex.code()).isEqualTo(Code.RATE_LIMITED);
                    assertThat(ex.retryAt()).isEqualTo(NOW.plusSeconds(5));
                });

        var exceedsRemaining = new Fixture(
                new Reply(503, Map.of("Retry-After", List.of("Sat, 10 Oct 2026 12:01:00 GMT")), "{}"));
        assertThatExceptionOfType(TicketException.class)
                .isThrownBy(() -> new YouTrackTransport(exceedsRemaining.client, 1024, CLOCK)
                        .read(ENDPOINT, AUTH, new Gate(Duration.ofSeconds(10))))
                .satisfies(ex -> {
                    assertThat(ex.code()).isEqualTo(Code.PROVIDER_ERROR);
                    assertThat(ex.retryAt()).isEqualTo(NOW.plusSeconds(60));
                });
        assertThat(exceedsRemaining.requests).hasSize(1);
    }

    @Test
    void enforcesStreamingResponseByteLimitAndTimeouts() {
        HttpClient client = mock(HttpClient.class);
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        when(client.<byte[]>sendAsync(any(), any())).thenAnswer(invocation -> {
            HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
            var info = mock(HttpResponse.ResponseInfo.class);
            when(info.statusCode()).thenReturn(200);
            when(info.headers()).thenReturn(HttpHeaders.of(Map.of(), (k, v) -> true));
            var subscriber = handler.apply(info);
            subscriber.onSubscribe(subscription);
            if (!subscriber.getBody().toCompletableFuture().isDone()) {
                subscriber.onNext(List.of(ByteBuffer.wrap("0123456789ABCDEF".getBytes(StandardCharsets.UTF_8))));
            }
            return subscriber.getBody().toCompletableFuture();
        });

        var transport = new YouTrackTransport(client, 8, CLOCK);
        assertThatExceptionOfType(TicketException.class)
                .isThrownBy(() -> transport.read(ENDPOINT, AUTH, new Gate(Duration.ofSeconds(10))))
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(Code.LIMIT_EXCEEDED));
        verify(subscription).cancel();

        HttpClient timeoutClient = mock(HttpClient.class);
        when(timeoutClient.<byte[]>sendAsync(any(), any())).thenReturn(
                CompletableFuture.failedFuture(new HttpTimeoutException("timed out")));
        assertThatExceptionOfType(TicketException.class)
                .isThrownBy(() -> new YouTrackTransport(timeoutClient, 1024, CLOCK)
                        .read(ENDPOINT, AUTH, new Gate(Duration.ofSeconds(10))))
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(Code.TIMEOUT));

        HttpClient ioErrorClient = mock(HttpClient.class);
        when(ioErrorClient.<byte[]>sendAsync(any(), any())).thenReturn(
                CompletableFuture.failedFuture(new IOException("reset")));
        assertThatExceptionOfType(TicketException.class)
                .isThrownBy(() -> new YouTrackTransport(ioErrorClient, 1024, CLOCK)
                        .read(ENDPOINT, AUTH, new Gate(Duration.ofSeconds(10))))
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(Code.PROVIDER_ERROR));
    }

    @Test
    void drainsAllowedErrorMissesWithoutCancellingSubscription() {
        String badQueryBody = "{\"error\":\"invalid_query\"}";
        String notFoundBody = "{\"error\":\"Not Found\"}";
        var fixture = new Fixture(
                new Reply(400, Map.of(), badQueryBody),
                new Reply(404, Map.of(), notFoundBody));
        var transport = new YouTrackTransport(fixture.client, 1024, CLOCK);
        var gate = new Gate(Duration.ofSeconds(10));

        assertThat(transport.readQuery(ENDPOINT, AUTH, gate)).isInstanceOf(QueryResponse.Rejected.class);
        assertThat(transport.readOptional(ENDPOINT, AUTH, gate)).isEqualTo(Optional.empty());
        assertThat(gate.bytes).isEqualTo(badQueryBody.length() + notFoundBody.length());
        assertThat(fixture.subscriptions).hasSize(2);
        for (Flow.Subscription subscription : fixture.subscriptions) {
            verify(subscription, times(2)).request(1);
            verify(subscription, never()).cancel();
        }
    }

    private static void assertFailure(int status, Code expected) {
        var fixture = new Fixture(new Reply(status, Map.of(), "{}"));
        var transport = new YouTrackTransport(fixture.client, 1024, CLOCK);
        assertThatExceptionOfType(TicketException.class)
                .isThrownBy(() -> transport.read(ENDPOINT, AUTH, new Gate(Duration.ofSeconds(10))))
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(expected));
    }

    private record Reply(int status, Map<String, List<String>> headers, String body) { }

    private static final class Fixture {
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<Flow.Subscription> subscriptions = new ArrayList<>();
        private final HttpClient client = mock(HttpClient.class);

        private Fixture(Reply... replies) {
            var remaining = new ArrayDeque<>(List.of(replies));
            when(client.<byte[]>sendAsync(any(), any())).thenAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                Reply reply = remaining.remove();
                var headers = HttpHeaders.of(reply.headers(), (k, v) -> true);
                var info = mock(HttpResponse.ResponseInfo.class);
                when(info.headers()).thenReturn(headers);
                when(info.statusCode()).thenReturn(reply.status());
                var subscriber = handler.apply(info);
                var subscription = mock(Flow.Subscription.class);
                subscriptions.add(subscription);
                subscriber.onSubscribe(subscription);
                if (!subscriber.getBody().toCompletableFuture().isDone()) {
                    subscriber.onNext(List.of(ByteBuffer.wrap(reply.body().getBytes(StandardCharsets.UTF_8))));
                    subscriber.onComplete();
                }
                return subscriber.getBody().thenApply(bytes -> {
                    @SuppressWarnings("unchecked")
                    HttpResponse<byte[]> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(reply.status());
                    when(response.headers()).thenReturn(headers);
                    when(response.body()).thenReturn(bytes);
                    return response;
                }).toCompletableFuture();
            });
        }
    }

    private static final class Gate implements TicketRequestGate {
        private final Duration remaining;
        private long bytes;

        private Gate(Duration remaining) {
            this.remaining = remaining;
        }

        @Override
        public Duration beforeRequest() {
            return Duration.ofSeconds(10);
        }

        @Override
        public void receivedBytes(long count) {
            bytes += count;
        }

        @Override
        public void acceptedObjects(int count) { }

        @Override
        public void check() { }

        @Override
        public Duration remaining() {
            return remaining;
        }
    }
}
