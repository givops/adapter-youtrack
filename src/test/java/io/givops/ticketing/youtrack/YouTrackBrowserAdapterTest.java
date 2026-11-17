package io.givops.ticketing.youtrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.givops.adapters.testkit.AdapterTestkit;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketBrowserModels.*;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketRequestGate;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class YouTrackBrowserAdapterTest {
    private static final Instant NOW = Instant.parse("2026-11-17T09:00:00Z");
    private static final InstanceSource SOURCE = new InstanceSource(UUID.randomUUID(), "yt", TicketProvider.YOUTRACK,
            URI.create("https://example.test/youtrack"), 1);
    private static final BrowserRequest REQUEST = new BrowserRequest(SOURCE, UUID.randomUUID(), "0-1", "YT", null, NOW);
    private static final TicketRef REF = new TicketRef(SOURCE.instanceId(), "0-1", "2-1");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PROJECT = "{\"id\":\"0-1\",\"shortName\":\"YT\"}";

    @Test void describesImplementedCapabilitiesWithoutHttpAndRejectsInvalidAccess() {
        var fixture = new Fixture();
        var descriptor = fixture.adapter.describe(REQUEST, fixture.access, fixture.gate);
        assertThat(descriptor.queryFields()).containsExactly("text:key-title-literal-nfc", "statuses", "categories", "labels:any");
        assertThat(descriptor.lifecycles()).containsExactly(Lifecycle.ACTIVE);
        assertThat(descriptor.sorts()).containsExactly("key-asc");
        assertThat(descriptor.snapshot()).isFalse();
        assertThat(descriptor.content()).isTrue();
        assertThat(descriptor.assets()).isFalse();
        failure(Code.SUPERSEDED, () -> fixture.adapter.describe(REQUEST, () -> "foreign", fixture.gate));
        fixture.lease.invalidate();
        failure(Code.SUPERSEDED, () -> fixture.adapter.describe(REQUEST, fixture.access, fixture.gate));
        assertThat(fixture.requests).isEmpty();
    }

    @Test void combinesFamiliesWithAndAndValuesWithOrAndPaginatesFilteredResults() {
        String body = "[" + String.join(",",
                issue(4, "YT-4", "Café braces {x}: -State", "Open", "Task", "red"),
                issue(3, "YT-3", "Café braces {x}: -State", "Fixed", "Bug", "green"),
                issue(2, "YT-2", "Café braces {x}: -State", "Fixed", "Feature", "blue"),
                issue(1, "YT-1", "Café braces {x}: -State", "Open", "Bug", "red")) + "]";
        var fixture = new Fixture(PROJECT, body, PROJECT, body);
        var firstQuery = new BrowseQuery("CAFÉ braces {x}: -State", Lifecycle.ACTIVE,
                List.of("Open", "Fixed"), List.of("Bug", "Feature"), List.of("blue", "red"), "key-asc", 1, null);
        var first = fixture.adapter.list(REQUEST, firstQuery, fixture.access, fixture.gate);
        assertThat(first.items()).extracting(TicketSummary::displayKey).containsExactly("YT-1");
        assertThat(first.exactTotal()).isNull();
        assertThat(first.revision().immutable()).isFalse();
        assertThat(first.capturedAt()).isEqualTo(NOW);
        var secondQuery = new BrowseQuery(firstQuery.text(), firstQuery.lifecycle(), firstQuery.statuses(), firstQuery.categories(),
                firstQuery.labels(), firstQuery.sort(), 1, first.nextContinuation());
        var second = fixture.adapter.list(withRevision(first.revision()), secondQuery, fixture.access, fixture.gate);
        assertThat(second.items()).extracting(TicketSummary::displayKey).containsExactly("YT-2");
        assertThat(second.nextContinuation()).isNull();
        assertThat(second.revision()).isEqualTo(first.revision());
        assertThat(fixture.requests).allSatisfy(request -> {
            assertThat(request.uri().toString()).startsWith("https://example.test/youtrack/api/");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer synthetic-token");
            assertThat(request.uri().getRawQuery()).doesNotContain("description", "comments");
        });
        assertThat(parameter(fixture.requests.get(1), "query")).isEqualTo("project: {YT} sort by: {issue id} asc");
        assertThat(fixture.gate.bytes).isPositive();
        assertThat(fixture.gate.objects).isEqualTo(10);
    }

    @Test void searchesOnlyKeyAndTitleAndTreatsQuerySyntaxAsLiteralText() {
        String body = "[" + issue(1, "YT-1", "Literal {State}: Open", "Open", "Bug", "red")
                .replace("\"customFields\"", "\"description\":\"secret description only\",\"customFields\"") + "]";
        var fixture = new Fixture(PROJECT, body, PROJECT, body, PROJECT, body);
        assertThat(fixture.adapter.list(REQUEST, query("yt-1", null), fixture.access, fixture.gate).items()).hasSize(1);
        assertThat(fixture.adapter.list(REQUEST, query("{State}: Open", null), fixture.access, fixture.gate).items()).hasSize(1);
        assertThat(fixture.adapter.list(REQUEST, query("secret", null), fixture.access, fixture.gate).items()).isEmpty();
    }

    @Test void sortsKeysByNumericSuffixAndRequiresTheConfiguredProjectKey() {
        String body = "[" + issue(10, "YT-10", "Ten", "Open", "Bug", "red") + ","
                + issue(2, "YT-2", "Two", "Open", "Bug", "red") + ","
                + issue(1, "YT-1", "One", "Open", "Bug", "red") + "]";
        var fixture = new Fixture(PROJECT, body);
        var query = new BrowseQuery("", null, null, null, null, null, 100, null);
        assertThat(fixture.adapter.list(REQUEST, query, fixture.access, fixture.gate).items())
                .extracting(TicketSummary::displayKey).containsExactly("YT-1", "YT-2", "YT-10");
        var renamed = new Fixture("{\"id\":\"0-1\",\"shortName\":\"RENAMED\"}");
        failure(Code.INVALID_RESPONSE, () -> renamed.adapter.list(REQUEST, query, renamed.access, renamed.gate));
    }

    @Test void readsDetailAndExactMarkdownContentUsingStableGlobalIdentity() throws Exception {
        String description = "  # User's {content}\n\n[unsafe](javascript:alert(1)) ![image](attachment.png)\n";
        String content = MAPPER.writeValueAsString(Map.of("id", "2-1", "project", Map.of("id", "0-1"), "description", description));
        var fixture = new Fixture(issue(1, "YT-91", "Preserved title", "Won't fix", "Bug", "red"), content);
        var detail = fixture.adapter.detail(REQUEST, REF, fixture.access, fixture.gate);
        assertThat(detail.ticket().ref()).isEqualTo(REF);
        assertThat(detail.ticket().displayKey()).isEqualTo("YT-91");
        assertThat(detail.ticket().outcome()).isEqualTo(Outcome.CANCELLED);
        assertThat(detail.ticket().lifecycle()).isEqualTo(Lifecycle.ACTIVE);
        assertThat(detail.assigneeId()).isEqualTo("ring-user");
        assertThat(detail.releaseVersionIds()).containsExactly("88-1");
        assertThat(detail.navigation().ticketUrl()).isEqualTo(URI.create("https://example.test/youtrack/issue/YT-91"));
        var later = new BrowserRequest(SOURCE, REQUEST.ticketProjectId(), "0-1", "YT", detail.ticket().revision(), NOW.plusSeconds(1));
        var result = fixture.adapter.content(later, REF, fixture.access, fixture.gate);
        assertThat(result.text()).isEqualTo(description);
        assertThat(result.format()).isEqualTo(ContentFormat.MARKDOWN);
        assertThat(result.assets()).isEmpty();
        assertThat(result.links()).isEmpty();
        assertThat(result.revision()).isEqualTo(detail.ticket().revision());
        assertThat(result.toString()).doesNotContain(description);
        assertThat(fixture.requests).allSatisfy(request -> assertThat(request.uri().getPath()).endsWith("/issues/2-1"));
        assertThat(fixture.requests.getFirst().uri().getRawQuery()).doesNotContain("description");
        assertThat(fixture.requests.getLast().uri().getRawQuery()).isEqualTo("fields=id,project(id),isDraft,description");
    }

    @Test void preservesSingletonAssigneeArraysWithIdentityFallbacksAndLeavesMultipleUsersAmbiguous() {
        String issue = issue(1, "YT-1", "One", "Open", "Bug", "red");
        String original = "\"value\":{\"ringId\":\"ring-user\",\"id\":\"1-1\"}";
        var users = List.of(
                "[{\"ringId\":\"ring-user\",\"id\":\"1-1\",\"login\":\"user\"}]",
                "[{\"id\":\"1-1\",\"login\":\"user\"}]",
                "[{\"login\":\"user\"}]");
        var expected = List.of("ring-user", "1-1", "user");
        for (int index = 0; index < users.size(); index++) {
            var fixture = new Fixture(issue.replace(original, "\"value\":" + users.get(index)));
            assertThat(fixture.adapter.detail(REQUEST, REF, fixture.access, fixture.gate).assigneeId()).isEqualTo(expected.get(index));
        }
        for (String value : List.of("[]", "[{\"id\":\"1-1\"},{\"id\":\"1-2\"}]")) {
            var fixture = new Fixture(issue.replace(original, "\"value\":" + value));
            assertThat(fixture.adapter.detail(REQUEST, REF, fixture.access, fixture.gate).assigneeId()).isNull();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"null", "\"\""})
    void nullAndEmptyDescriptionsAreEmptyContent(String description) {
        var fixture = new Fixture("{\"id\":\"2-1\",\"project\":{\"id\":\"0-1\"},\"description\":" + description + "}");
        assertThat(fixture.adapter.content(REQUEST, REF, fixture.access, fixture.gate).text()).isEmpty();
    }

    @Test void rejectsUnsupportedLifecycleSortAndInvalidFilterBeforeHttp() {
        var fixture = new Fixture();
        for (var query : List.of(
                new BrowseQuery("", Lifecycle.ARCHIVED, null, null, null, null, 10, null),
                new BrowseQuery("", Lifecycle.DRAFT, null, null, null, null, 10, null),
                new BrowseQuery("", null, null, null, null, "updated-desc", 10, null),
                new BrowseQuery("", null, List.of("  "), null, null, null, 10, null),
                new BrowseQuery("", null, null, List.of("x\ny"), null, null, 10, null))) {
            failure(Code.INVALID_CONFIGURATION, () -> fixture.adapter.list(REQUEST, query, fixture.access, fixture.gate));
        }
        assertThat(fixture.requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"", "v1.0.abc", "v1.-1.abc", "v1.9999999999.abc", "v1.01.abc", "v2.1.abc", "v1.1.abc.extra"})
    void rejectsMalformedCursorBeforeHttp(String cursor) {
        var fixture = new Fixture();
        failure(Code.INVALID_CONFIGURATION, () -> fixture.adapter.list(REQUEST, query("", cursor), fixture.access, fixture.gate));
        assertThat(fixture.requests).isEmpty();
    }

    @Test void rejectsCursorFromDifferentQueryOrCapturedContextBeforeHttp() {
        var fixture = new Fixture(PROJECT, "[" + issue(1, "YT-1", "One", "Open", "Bug", "red") + ","
                + issue(2, "YT-2", "Two", "Open", "Bug", "red") + "]");
        var first = fixture.adapter.list(REQUEST, query("", null), fixture.access, fixture.gate);
        failure(Code.INVALID_CONFIGURATION, () -> fixture.adapter.list(REQUEST, query("changed", first.nextContinuation()), fixture.access, fixture.gate));
        var changedBinding = new BrowserRequest(SOURCE, UUID.randomUUID(), "0-1", "YT", null, NOW);
        failure(Code.INVALID_CONFIGURATION, () -> fixture.adapter.list(changedBinding, query("", first.nextContinuation()), fixture.access, fixture.gate));
        assertThat(fixture.requests).hasSize(2);
    }

    @Test void rejectsForeignIdentityOriginAndRevisionBeforeHttp() {
        var fixture = new Fixture();
        for (var ref : List.of(new TicketRef(UUID.randomUUID(), "0-1", "2-1"),
                new TicketRef(SOURCE.instanceId(), "0-2", "2-1"), new TicketRef(SOURCE.instanceId(), "0-1", "../2-1"),
                new TicketRef(SOURCE.instanceId(), "0-1", "YT-1"))) {
            failure(Code.INVALID_CONFIGURATION, () -> fixture.adapter.detail(REQUEST, ref, fixture.access, fixture.gate));
            failure(Code.INVALID_CONFIGURATION, () -> fixture.adapter.content(REQUEST, ref, fixture.access, fixture.gate));
        }
        failure(Code.STALE_CONTEXT, () -> fixture.adapter.describe(withRevision(new SourceRevision("other", "forged", false)), fixture.access, fixture.gate));
        var otherSource = new InstanceSource(SOURCE.instanceId(), "yt", TicketProvider.YOUTRACK, URI.create("https://other.test/youtrack"), 1);
        var otherRequest = new BrowserRequest(otherSource, REQUEST.ticketProjectId(), "0-1", "YT", null, NOW);
        failure(Code.SUPERSEDED, () -> fixture.adapter.describe(otherRequest, fixture.access, fixture.gate));
        assertThat(fixture.requests).isEmpty();
    }

    @Test void rejectsAContinuationAfterTheRemoteResultSetShrinks() {
        var fixture = new Fixture(PROJECT, "[" + issue(1, "YT-1", "One", "Open", "Bug", "red") + ","
                + issue(2, "YT-2", "Two", "Open", "Bug", "red") + "]", PROJECT, "[]");
        var first = fixture.adapter.list(REQUEST, query("", null), fixture.access, fixture.gate);
        failure(Code.STALE_CONTEXT, () -> fixture.adapter.list(withRevision(first.revision()), query("", first.nextContinuation()), fixture.access, fixture.gate));
    }

    @Test void detectsRevocationDuringResponseAndHonorsGateCancellation() {
        var fixture = new Fixture(issue(1, "YT-1", "One", "Open", "Bug", "red"));
        fixture.duringBody = fixture.lease::invalidate;
        failure(Code.SUPERSEDED, () -> fixture.adapter.detail(REQUEST, REF, fixture.access, fixture.gate));
        var cancelled = new Fixture();
        cancelled.gate.cancelled = true;
        failure(Code.TIMEOUT, () -> cancelled.adapter.describe(REQUEST, cancelled.access, cancelled.gate));
        assertThat(cancelled.requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"id\":\"2-2\",\"project\":{\"id\":\"0-1\"},\"description\":\"x\"}",
            "{\"id\":\"2-1\",\"project\":{\"id\":\"0-2\"},\"description\":\"x\"}",
            "{\"id\":\"2-1\",\"id\":\"2-1\",\"project\":{\"id\":\"0-1\"}}",
            "{\"id\":\"2-1\",\"project\":{\"id\":\"0-1\"},\"description\":{}}",
            "{\"id\":\"2-1\",\"project\":{\"id\":\"0-1\"}} {}"})
    void rejectsMismatchedOrMalformedContentResponses(String body) {
        var fixture = new Fixture(body);
        failure(Code.INVALID_RESPONSE, () -> fixture.adapter.content(REQUEST, REF, fixture.access, fixture.gate));
    }

    @Test void rejectsDuplicateListIdentityAndWrongProject() {
        String issue = issue(1, "YT-1", "One", "Open", "Bug", "red");
        var duplicate = new Fixture(PROJECT, "[" + issue + "," + issue + "]");
        failure(Code.INVALID_RESPONSE, () -> duplicate.adapter.list(REQUEST, query("", null), duplicate.access, duplicate.gate));
        var foreign = new Fixture(PROJECT, "[" + issue.replace("\"0-1\"", "\"0-2\"") + "]");
        failure(Code.INVALID_RESPONSE, () -> foreign.adapter.list(REQUEST, query("", null), foreign.access, foreign.gate));
    }

    @Test void rejectsMalformedDetailIdentityAndFields() {
        String issue = issue(1, "YT-1", "One", "Open", "Bug", "red");
        for (String body : List.of(issue.replace("\"2-1\"", "\"2-2\""), issue.replace("\"0-1\"", "\"0-2\""),
                issue.replace("\"YT-1\"", "\"OTHER-1\""), issue.replace("\"updated\":2000", "\"updated\":\"bad\""),
                issue.replace("\"summary\":\"One\"", "\"summary\":null"))) {
            var fixture = new Fixture(body);
            failure(Code.INVALID_RESPONSE, () -> fixture.adapter.detail(REQUEST, REF, fixture.access, fixture.gate));
        }
    }

    @Test void resolvedIssuesRemainActiveAndDraftsAreExcluded() {
        String draft = "{\"id\":\"2-2\",\"project\":{\"id\":\"0-1\"},\"isDraft\":true}";
        var fixture = new Fixture(PROJECT, "[" + issue(1, "YT-1", "One", "Fixed", "Bug", "red") + "," + draft + "]", draft, draft);
        var page = fixture.adapter.list(REQUEST, query("", null), fixture.access, fixture.gate);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.lifecycle()).isEqualTo(Lifecycle.ACTIVE);
            assertThat(item.outcome()).isEqualTo(Outcome.SUCCEEDED);
        });
        failure(Code.NOT_FOUND, () -> fixture.adapter.content(REQUEST, new TicketRef(SOURCE.instanceId(), "0-1", "2-2"), fixture.access, fixture.gate));
        failure(Code.NOT_FOUND, () -> fixture.adapter.detail(REQUEST, new TicketRef(SOURCE.instanceId(), "0-1", "2-2"), fixture.access, fixture.gate));
    }

    @Test void handlesMissingCustomFieldsWithoutFalseFilterMatches() {
        String issue = "{\"id\":\"2-1\",\"idReadable\":\"YT-1\",\"summary\":\"One\",\"created\":1,\"updated\":2,\"project\":{\"id\":\"0-1\"}}";
        var fixture = new Fixture(PROJECT, "[" + issue + "]");
        var query = new BrowseQuery("", null, List.of("Open"), List.of("Bug"), null, null, 10, null);
        assertThat(fixture.adapter.list(REQUEST, query, fixture.access, fixture.gate).items()).isEmpty();
    }

    @Test void followsBoundedRemotePaginationAndRejectsIncompleteScan() {
        String full = IntStream.rangeClosed(1, 100).mapToObj(i -> issue(i, "YT-" + i, "One", "Open", "Bug", "red"))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        var fixture = new Fixture(PROJECT, full, "[]");
        assertThat(fixture.adapter.list(REQUEST, query("YT-100", null), fixture.access, fixture.gate).items()).hasSize(1);
        assertThat(parameter(fixture.requests.getLast(), "$skip")).isEqualTo("100");
        var limited = new Fixture(limits(true, 1, 10000, 2097152, 16777216), PROJECT, full);
        failure(Code.LIMIT_EXCEEDED, () -> limited.adapter.list(REQUEST, query("", null), limited.access, limited.gate));
    }

    @Test void enforcesObjectByteAndContentBoundsAndDisabledModule() {
        var objects = new Fixture(limits(true, 20, 1, 2097152, 16777216), PROJECT,
                "[" + issue(1, "YT-1", "One", "Open", "Bug", "red") + "]");
        failure(Code.LIMIT_EXCEEDED, () -> objects.adapter.list(REQUEST, query("", null), objects.access, objects.gate));
        var bytes = new Fixture(limits(true, 20, 10000, 20, 20), PROJECT);
        failure(Code.LIMIT_EXCEEDED, () -> bytes.adapter.list(REQUEST, query("", null), bytes.access, bytes.gate));
        String smallIssue = "[" + issue(1, "YT-1", "One", "Open", "Bug", "red") + "]";
        int perResponse = smallIssue.getBytes(StandardCharsets.UTF_8).length + 1;
        var aggregate = new Fixture(limits(true, 20, 10000, perResponse, perResponse), PROJECT, smallIssue);
        failure(Code.LIMIT_EXCEEDED, () -> aggregate.adapter.list(REQUEST, query("", null), aggregate.access, aggregate.gate));
        var content = new Fixture("{\"id\":\"2-1\",\"project\":{\"id\":\"0-1\"},\"description\":\"" + "é".repeat(524289) + "\"}");
        failure(Code.LIMIT_EXCEEDED, () -> content.adapter.content(REQUEST, REF, content.access, content.gate));
        var disabled = new Fixture(limits(false, 20, 10000, 2097152, 16777216), new String[0]);
        failure(Code.DISABLED, () -> disabled.adapter.describe(REQUEST, disabled.access, disabled.gate));
    }

    @Test void stopsBeforeSendingARequestBeyondTheAttemptBudget() {
        var normal = limits(true, 20, 10000, 2097152, 16777216);
        var oneAttempt = new TicketProperties(normal.enabled(), normal.refreshTimeout(), normal.requestTimeout(), 1,
                normal.maxPages(), normal.maxResponseBytes(), normal.maxTotalResponseBytes(), normal.maxIssueKeys(),
                normal.maxReleaseIssues(), normal.maxReleaseCommits(), normal.maxReferences(), normal.maxSourceTextBytes(),
                normal.maxBindings(), normal.maxMergeRequests(), normal.maxObjects(), normal.freshness(), normal.retainedAttempts(), normal.maxAssignedIssues());
        var fixture = new Fixture(oneAttempt, PROJECT);
        failure(Code.LIMIT_EXCEEDED, () -> fixture.adapter.list(REQUEST, query("", null), fixture.access, fixture.gate));
        assertThat(fixture.requests).hasSize(1);
    }

    @Test void preservesTransportAuthenticationMissingAndRedirectClassifications() {
        for (var entry : Map.of(401, Code.AUTHENTICATION_FAILED, 403, Code.AUTHORIZATION_FAILED, 404, Code.NOT_FOUND, 302, Code.PROVIDER_ERROR).entrySet()) {
            var fixture = new Fixture(new Reply(entry.getKey(), "ignored"));
            failure(entry.getValue(), () -> fixture.adapter.content(REQUEST, REF, fixture.access, fixture.gate));
            assertThat(fixture.requests).hasSize(1);
        }
    }

    private static BrowseQuery query(String text, String cursor) { return new BrowseQuery(text, null, null, null, null, null, 1, cursor); }
    private static BrowserRequest withRevision(SourceRevision revision) {
        return new BrowserRequest(SOURCE, REQUEST.ticketProjectId(), "0-1", "YT", revision, NOW);
    }
    private static void failure(Code code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(TicketException.class, exception -> assertThat(exception.code()).isEqualTo(code));
    }
    private static TicketProperties limits(boolean enabled, int pages, int objects, long responseBytes, long totalBytes) {
        return new TicketProperties(enabled, Duration.ofMinutes(2), Duration.ofSeconds(10), 100, pages,
                responseBytes, totalBytes, 500, 500, 1000, 5000, 8388608, 20, 500, objects, Duration.ofMinutes(15), 50, 500);
    }
    private static String issue(int id, String key, String title, String status, String type, String label) {
        boolean resolved = status.equals("Fixed") || status.equals("Won't fix");
        return """
                {"id":"2-%d","idReadable":"%s","summary":"%s","project":{"id":"0-1","shortName":"YT"},
                 "created":1000,"updated":2000,"resolved":%s,"isDraft":false,"tags":[{"name":"%s"}],
                 "customFields":[{"name":"State","value":{"name":"%s","isResolved":%s}},
                  {"name":"Type","value":{"name":"%s"}}, {"name":"Assignee","value":{"ringId":"ring-user","id":"1-1"}},
                  {"name":"Fix versions","value":[{"id":"88-1"}]}]}
                """.formatted(id, key, title, resolved ? "2000" : "null", label, status, resolved, type);
    }
    private static String parameter(HttpRequest request, String name) {
        for (String part : request.uri().getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals(name)) return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        return "";
    }
    private record Reply(int status, String body) { }
    private static final class Fixture {
        final List<HttpRequest> requests = new ArrayList<>();
        final Gate gate = new Gate();
        final AdapterTestkit.MutableCredentialLease lease = AdapterTestkit.lease(Map.of("apiToken", "synthetic-token"),
                Map.of("baseUrl", SOURCE.baseUrl().toString(), "instanceKey", "yt"), "1");
        final YouTrackCredentials credentials = new YouTrackCredentials(request -> lease, YouTrackTestCredentials.SIGNER);
        final YouTrackCredentials.Captured access = credentials.capture(SOURCE);
        final YouTrackBrowserAdapter adapter;
        Runnable duringBody = () -> { };
        Fixture(String... bodies) { this(limits(true, 20, 10000, 2097152, 16777216), bodies); }
        Fixture(TicketProperties limits, String... bodies) { this(limits, java.util.Arrays.stream(bodies).map(body -> new Reply(200, body)).toArray(Reply[]::new)); }
        Fixture(Reply reply) { this(limits(true, 20, 10000, 2097152, 16777216), reply); }
        Fixture(TicketProperties limits, Reply... replies) {
            var queue = new ArrayDeque<>(List.of(replies));
            var client = mock(HttpClient.class);
            when(client.<byte[]>sendAsync(any(), any())).thenAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                var reply = queue.remove();
                var headers = HttpHeaders.of(Map.of(), (key, value) -> true);
                var info = mock(HttpResponse.ResponseInfo.class);
                when(info.headers()).thenReturn(headers);
                when(info.statusCode()).thenReturn(reply.status());
                var subscriber = handler.apply(info);
                subscriber.onSubscribe(mock(Flow.Subscription.class));
                duringBody.run();
                if (!subscriber.getBody().toCompletableFuture().isDone()) {
                    subscriber.onNext(List.of(ByteBuffer.wrap(reply.body().getBytes(StandardCharsets.UTF_8))));
                    subscriber.onComplete();
                }
                return subscriber.getBody().thenApply(bytes -> {
                    @SuppressWarnings("unchecked") HttpResponse<byte[]> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(reply.status());
                    when(response.headers()).thenReturn(headers);
                    when(response.body()).thenReturn(bytes);
                    return response;
                }).toCompletableFuture();
            });
            adapter = new YouTrackBrowserAdapter(credentials, limits,
                    new YouTrackTransport(client, limits.maxResponseBytes(), Clock.fixed(NOW, ZoneOffset.UTC)), MAPPER);
        }
    }
    private static final class Gate implements TicketRequestGate {
        long bytes;
        int objects;
        boolean cancelled;
        @Override public Duration beforeRequest() { check(); return Duration.ofSeconds(10); }
        @Override public Duration remaining() { return Duration.ofMinutes(2); }
        @Override public void receivedBytes(long count) { bytes += count; }
        @Override public void acceptedObjects(int count) { objects += count; }
        @Override public void check() { if (cancelled) throw new TicketException(Code.TIMEOUT, "Cancelled fixture request."); }
    }
}
