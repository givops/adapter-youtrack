package io.givops.ticketing.youtrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.IssueIdentity;
import io.givops.ticketing.model.TicketModels.IssueObservation;
import io.givops.ticketing.model.TicketModels.ProjectQuery;
import io.givops.ticketing.model.TicketModels.ProviderInput;
import io.givops.ticketing.model.TicketModels.ReleaseMappingState;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class YouTrackAdapterTest {
    private static final URI ROOT_SITE = URI.create("https://team.youtrack.cloud");
    private static final URI CONTEXT_SITE = URI.create("https://host.example.com/youtrack/");
    private static final String NORMALIZED_CONTEXT_SITE = "https://host.example.com/youtrack";
    private static final String TOKEN = "synthetic-youtrack-token";
    private static final String PROJECT_ID = "0-1";
    private static final UUID BINDING_ID = UUID.fromString("b76eaf5d-645f-4b50-a8aa-70b46af7aa24");
    private static final InstanceSource SOURCE = new InstanceSource(
            UUID.fromString("2972b7b5-03cf-4058-a4a2-17a2a6c3897b"), "yt", TicketProvider.YOUTRACK, CONTEXT_SITE, 0);
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TicketProperties LIMITS = limits(true, 20, 500, 500);

    private static final String PROJECT_JSON = """
            {"id":"0-1","shortName":"YT","name":"YouTrack Demo"}
            """;
    private static final String CUSTOM_FIELDS_JSON = """
            [
              {"id":"cf-affected","$type":"VersionProjectCustomField","field":{"id":"f-av","name":"Affected versions","fieldType":{"id":"version[*]","valueType":"version"}},"bundle":{"id":"bundle-affected","$type":"VersionBundle"}},
              {"id":"cf-fix-singular","$type":"VersionProjectCustomField","field":{"id":"f-fv1","name":"Fix version","fieldType":{"id":"version[1]","valueType":"version"}},"bundle":{"id":"bundle-legacy","$type":"VersionBundle"}},
              {"id":"cf-fix","$type":"VersionProjectCustomField","field":{"id":"f-fv","name":"Fix versions","fieldType":{"id":"version[*]","valueType":"version"}},"bundle":{"id":"bundle-fix","$type":"VersionBundle"}},
              {"id":"cf-assignee","$type":"UserProjectCustomField","field":{"id":"f-as","name":"Assignee","fieldType":{"id":"user[1]","valueType":"user"}},"bundle":{"id":"bundle-users","$type":"UserBundle"}}
            ]
            """;

    @Test
    void validatesOriginAndSingleContextPathNormalization() {
        assertThat(YouTrackBaseUrls.normalizeYouTrackBaseUrl(URI.create("https://team.youtrack.cloud/"), false,
                YouTrackConnection::invalidConfiguration)).isEqualTo(ROOT_SITE);
        assertThat(YouTrackBaseUrls.normalizeYouTrackBaseUrl(CONTEXT_SITE, false,
                YouTrackConnection::invalidConfiguration).toString()).isEqualTo(NORMALIZED_CONTEXT_SITE);
        assertThat(YouTrackBaseUrls.normalizeYouTrackBaseUrl(URI.create("https://127.0.0.1:8443/youtrack/"), true,
                YouTrackConnection::invalidConfiguration).toString()).isEqualTo("https://127.0.0.1:8443/youtrack");
        assertThat(YouTrackBaseUrls.contextPath(URI.create(NORMALIZED_CONTEXT_SITE))).isEqualTo("/youtrack");
        assertThat(YouTrackBaseUrls.contextPath(ROOT_SITE)).isEmpty();

        for (String invalid : List.of(
                "http://team.youtrack.cloud",
                "https://team.youtrack.cloud/a/b",
                "https://team.youtrack.cloud/api",
                "https://team.youtrack.cloud/issue",
                "https://user:pass@team.youtrack.cloud",
                "https://team.youtrack.cloud?x=1",
                "https://team.youtrack.cloud#frag",
                "https://host.example.com:8443/youtrack")) {
            assertFailure(Code.INVALID_CONFIGURATION, () -> YouTrackBaseUrls.normalizeYouTrackBaseUrl(
                    URI.create(invalid), false, YouTrackConnection::invalidConfiguration));
        }

        assertFailure(Code.INVALID_CONFIGURATION, () -> new YouTrackConnection(
                YouTrackTestCredentials.SIGNER, SOURCE,
                new YouTrackProperties.Instance(NORMALIZED_CONTEXT_SITE, "bad token", "1")));
        assertFailure(Code.INVALID_CONFIGURATION, () -> new YouTrackConnection(
                YouTrackTestCredentials.SIGNER, SOURCE,
                new YouTrackProperties.Instance(ROOT_SITE.toString(), TOKEN, "1")));

    }

    @Test
    void readsProjectFixVersionsReleaseMembershipAssignedAndStatusCategoriesUnderContextPath() {
        long releaseMillis = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli();
        String versionsJson = """
                [
                  {"id":"88-1","name":"1.2.2","released":true,"archived":false,"releaseDate":%d},
                  {"id":"88-2","name":"1.2.3","released":false,"archived":false,"releaseDate":null}
                ]
                """.formatted(releaseMillis);
        String requestedIssue = issue("2-10", "YT-10", "Fix login", "Submitted", false, null, "Bug", "High",
                List.of("88-1"), "{\"ringId\":\"ring-alice\",\"id\":\"1-1\",\"login\":\"alice\"}");
        String releaseIssue = issue("2-11", "YT-11", "Ship release", "Fixed", true,
                Instant.parse("2026-10-09T11:00:00Z").toEpochMilli(), "Task", "Critical",
                List.of("88-2"), null);
        String assignedSingle = issue("2-12", "YT-12", "Investigate trace", "In Progress", false, null,
                "Task", "Normal", List.of(), "{\"id\":\"1-2\",\"login\":\"bob\"}");
        String assignedMultiUser = issue("2-13", "YT-13", "Shared pair task", "Open", false, null,
                "Task", "Low", List.of(), "[{\"ringId\":\"u1\"},{\"ringId\":\"u2\"}]");

        var fixture = new Fixture(
                new Reply(PROJECT_JSON),
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply(versionsJson),
                new Reply("[" + requestedIssue + "]"),
                new Reply("[" + releaseIssue + "]"),
                new Reply("[" + assignedSingle + "," + assignedMultiUser + "]"));

        var result = fixture.adapter(settings(NORMALIZED_CONTEXT_SITE), LIMITS)
                .read(input(SOURCE, Set.of("YT-10", "YT-999"), "1.2.3"), new Gate())
                .projects().getFirst();

        assertThat(fixture.requests).hasSize(6);
        assertThat(fixture.requests.get(0).uri().toString())
                .startsWith(NORMALIZED_CONTEXT_SITE + "/api/admin/projects/0-1?");
        assertThat(fixture.requests.get(2).uri().toString())
                .startsWith(NORMALIZED_CONTEXT_SITE + "/api/admin/customFieldSettings/bundles/version/bundle-fix/values?");
        assertThat(queryParam(fixture.requests.get(3))).isEqualTo("project: {YT} {issue id}: YT-10, YT-999 sort by: {issue id} asc");
        assertThat(queryParam(fixture.requests.get(4))).isEqualTo("project: {YT} {Fix versions}: {1.2.3} sort by: {issue id} asc");
        assertThat(queryParam(fixture.requests.get(5))).isEqualTo("project: {YT} has: {Assignee} #Unresolved sort by: {issue id} asc");
        assertThat(fixture.requests.get(0).headers().firstValue("Authorization")).contains("Bearer " + TOKEN);

        assertThat(result.project().webUrl()).isEqualTo(URI.create(NORMALIZED_CONTEXT_SITE + "/projects/0-1"));
        assertThat(result.versions()).hasSize(2);
        assertThat(result.versions().getFirst().releaseDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(result.unresolvedKeys()).containsExactly("YT-999");
        assertThat(result.releaseMembership().state()).isEqualTo(ReleaseMappingState.MATCHED);
        assertThat(result.releaseMembership().externalVersionId()).isEqualTo("88-2");
        assertThat(result.releaseMembership().issues()).containsExactly(new IssueIdentity(PROJECT_ID, "2-11"));
        assertThat(result.assignedIssues()).containsExactly(new IssueIdentity(PROJECT_ID, "2-12"));

        assertThat(result.issues()).hasSize(3);
        Map<String, IssueObservation> byKey = result.issues().stream()
                .collect(java.util.stream.Collectors.toMap(IssueObservation::issueKey, i -> i));
        assertThat(byKey.get("YT-10").statusCategory()).isEqualTo("new");
        assertThat(byKey.get("YT-10").resolution()).isNull();
        assertThat(byKey.get("YT-10").assigneeAccountId()).isEqualTo("ring-alice");
        assertThat(byKey.get("YT-10").webUrl()).isEqualTo(URI.create(NORMALIZED_CONTEXT_SITE + "/issue/YT-10"));
        assertThat(byKey.get("YT-11").statusCategory()).isEqualTo("done");
        assertThat(byKey.get("YT-11").resolution()).isEqualTo("Fixed");
        assertThat(byKey.get("YT-12").statusCategory()).isEqualTo("indeterminate");
        assertThat(byKey.get("YT-12").assigneeAccountId()).isEqualTo("1-2");
        assertThat(byKey).doesNotContainKey("YT-13");
    }

    @Test
    void handlesMissingOrAmbiguousVersionsAndAbsentFixVersionField() {
        var noFixVersions = new Fixture(
                new Reply(PROJECT_JSON),
                new Reply("""
                        [{"id":"cf-affected","$type":"VersionProjectCustomField","field":{"id":"f-av","name":"Affected versions","fieldType":{"valueType":"version"}},"bundle":{"id":"bundle-affected","$type":"VersionBundle"}}]
                        """),
                new Reply("[]"));
        var unsupported = noFixVersions.adapter(settings(NORMALIZED_CONTEXT_SITE), LIMITS)
                .read(input(SOURCE, Set.of("YT-1"), "1.2.3"), new Gate()).projects().getFirst();
        assertThat(unsupported.releaseMembership().state()).isEqualTo(ReleaseMappingState.UNSUPPORTED);
        assertThat(unsupported.versions()).isEmpty();
        assertThat(unsupported.assignedIssues()).isEmpty();

        var ambiguousVersions = new Fixture(
                new Reply(PROJECT_JSON),
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply("""
                        [{"id":"88-1","name":"1.2.3","released":true,"archived":false},
                         {"id":"88-2","name":"1.2.3","released":false,"archived":false}]
                        """),
                new Reply("[]"));
        var ambiguous = ambiguousVersions.adapter(settings(NORMALIZED_CONTEXT_SITE), LIMITS)
                .read(input(SOURCE, Set.of(), "1.2.3"), new Gate()).projects().getFirst();
        assertThat(ambiguous.releaseMembership().state()).isEqualTo(ReleaseMappingState.AMBIGUOUS);

        var notFoundVersions = new Fixture(
                new Reply(PROJECT_JSON),
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply("[{\"id\":\"88-1\",\"name\":\"1.2.2\",\"released\":true,\"archived\":false}]"),
                new Reply("[]"));
        var notFound = notFoundVersions.adapter(settings(NORMALIZED_CONTEXT_SITE), LIMITS)
                .read(input(SOURCE, Set.of(), "9.9.9"), new Gate()).projects().getFirst();
        assertThat(notFound.releaseMembership().state()).isEqualTo(ReleaseMappingState.NOT_FOUND);
    }

    @Test
    void enforcesPaginationAndObjectLimitsAndRejectsCrossProjectPayloads() {
        var pageLimit = new Fixture(
                new Reply(PROJECT_JSON),
                new Reply("[" + String.join(",", java.util.Collections.nCopies(100,
                        "{\"id\":\"cf\",\"field\":{\"name\":\"Custom\"}}")) + "]"));
        assertFailure(Code.LIMIT_EXCEEDED, () -> pageLimit.adapter(settings(NORMALIZED_CONTEXT_SITE),
                limits(true, 1, 500, 500)).read(input(SOURCE, Set.of(), null), new Gate()));

        var releaseLimit = new Fixture(
                new Reply(PROJECT_JSON),
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply("[{\"id\":\"88-2\",\"name\":\"1.2.3\",\"released\":false,\"archived\":false}]"),
                new Reply("["
                        + issue("2-1", "YT-1", "A", "Open", false, null, "Task", "Normal", List.of("88-2"), null)
                        + ","
                        + issue("2-2", "YT-2", "B", "Open", false, null, "Task", "Normal", List.of("88-2"), null)
                        + "]"));
        assertFailure(Code.LIMIT_EXCEEDED, () -> releaseLimit.adapter(settings(NORMALIZED_CONTEXT_SITE),
                limits(true, 20, 1, 500)).read(input(SOURCE, Set.of(), "1.2.3"), new Gate()));

        var crossProject = new Fixture(
                new Reply(PROJECT_JSON),
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply("[]"),
                new Reply("""
                        [{"id":"2-99","idReadable":"YT-99","summary":"Wrong project","created":1760000000000,"project":{"id":"0-99","shortName":"OTHER"}}]
                        """));
        assertFailure(Code.INVALID_RESPONSE, () -> crossProject.adapter(settings(NORMALIZED_CONTEXT_SITE), LIMITS)
                .read(input(SOURCE, Set.of("YT-99"), null), new Gate()));
    }

    @Test
    void fallsBackToPerIssueLookupWhenBatchYqlRejectsUnknownIssueKey() {
        String yt1 = issue("2-1", "YT-1", "Valid issue", "To do", false, null, "Task", "Normal", List.of(), null);
        String movedIssue = """
                {"id":"2-77","idReadable":"OTHER-77","summary":"Moved elsewhere","created":1760000000000,
                 "project":{"id":"0-99","shortName":"OTHER"},"customFields":[]}
                """;
        var fixture = new Fixture(
                new Reply(PROJECT_JSON),
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply("[]"),
                // Batch of 5 keys (sorted: YT-1, YT-2, YT-77, YT-88, YT-99) rejected with 400.
                new Reply(400, Map.of(), "{\"error\":\"invalid_query\"}"),
                // Left split (size 2 <= 4): YT-1, YT-2 -> 200 with YT-1 only.
                new Reply("[" + yt1 + "]"),
                // Right split (size 3 <= 4): YT-77, YT-88, YT-99 -> 400 -> individual lookups.
                new Reply(400, Map.of(), "{\"error\":\"invalid_query\"}"),
                // YT-77 moved to another project -> left unresolved.
                new Reply(200, Map.of(), movedIssue),
                // YT-88 missing -> 404 -> left unresolved.
                new Reply(404, Map.of(), "{\"error\":\"Not found\"}"),
                // YT-99 missing -> 404 -> left unresolved.
                new Reply(404, Map.of(), "{\"error\":\"Not found\"}"),
                // Assigned issues.
                new Reply("[]"));

        var result = fixture.adapter(settings(NORMALIZED_CONTEXT_SITE), LIMITS)
                .read(input(SOURCE, Set.of("YT-1", "YT-2", "YT-77", "YT-88", "YT-99", "EXT-999"), null), new Gate())
                .projects().getFirst();

        assertThat(result.issues()).extracting(IssueObservation::issueKey).containsExactly("YT-1");
        assertThat(result.unresolvedKeys()).containsExactlyInAnyOrder("YT-2", "YT-77", "YT-88", "YT-99", "EXT-999");

        var unauthorizedFallback = new Fixture(
                new Reply(PROJECT_JSON),
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply("[]"),
                new Reply(400, Map.of(), "{\"error\":\"invalid_query\"}"),
                new Reply(401, Map.of(), "{\"error\":\"unauthorized\"}"));
        assertFailure(Code.AUTHENTICATION_FAILED, () -> unauthorizedFallback.adapter(
                        settings(NORMALIZED_CONTEXT_SITE), LIMITS)
                .read(input(SOURCE, Set.of("YT-99"), null), new Gate()));
    }

    @Test
    void readsVersionMembershipsAcrossSelectedVersionsAndRejectsContradictions() {
        var i1 = issue("2-1", "YT-1", "Shared", "Open", false, null, "Task", "Normal", List.of("88-1", "88-2"), null);
        var i2 = issue("2-2", "YT-2", "Only one", "Open", false, null, "Task", "Normal", List.of("88-1"), null);
        var fixture = new Fixture(
                new Reply(PROJECT_JSON),
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply("""
                        [{"id":"88-1","name":"1.2.3","released":true,"archived":false},
                         {"id":"88-2","name":"1.2.4","released":false,"archived":false}]
                        """),
                new Reply("[" + i1 + "," + i2 + "]"),
                new Reply("[" + i1 + "]"));

        var adapter = fixture.adapter(settings(NORMALIZED_CONTEXT_SITE), LIMITS);
        var query = new ProjectQuery(BINDING_ID, PROJECT_ID, "YT", Set.of(), null);
        var result = adapter.readVersionMemberships(SOURCE, query, adapter.captureAccess(SOURCE),
                new Gate(), versions -> Set.of("88-1", "88-2"), 5, 10);

        assertThat(result.members().get("88-1")).extracting(IssueObservation::issueKey).containsExactly("YT-1", "YT-2");
        assertThat(result.members().get("88-2")).extracting(IssueObservation::issueKey).containsExactly("YT-1");

        var contradictory = new Fixture(
                new Reply(PROJECT_JSON),
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply("""
                        [{"id":"88-1","name":"1.2.3","released":true,"archived":false},
                         {"id":"88-2","name":"1.2.4","released":false,"archived":false}]
                        """),
                new Reply("[" + i1 + "]"),
                new Reply("[]"));
        var adapter2 = contradictory.adapter(settings(NORMALIZED_CONTEXT_SITE), LIMITS);
        assertFailure(Code.INVALID_RESPONSE, () -> adapter2.readVersionMemberships(SOURCE, query,
                adapter2.captureAccess(SOURCE), new Gate(), versions -> Set.of("88-1", "88-2"), 5, 10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Submitted", "Open", "New", "To Do"})
    void mapsOpenLikeStatesToNewStatusCategory(String status) {
        assertThat(YouTrackPayload.deriveStatusCategory(status, false, null)).isEqualTo("new");
    }

    private static YouTrackProperties settings(String expectedBaseUrl) {
        return new YouTrackProperties(
                Map.of("yt", new YouTrackProperties.Instance(expectedBaseUrl, TOKEN, "1")));
    }

    private static ProviderInput input(InstanceSource source, Set<String> keys, String release) {
        return new ProviderInput(source, List.of(new ProjectQuery(BINDING_ID, PROJECT_ID, "YT", keys, release)), NOW);
    }

    private static TicketProperties limits(boolean enabled, int pages, int releaseIssues, int assignedIssues) {
        return new TicketProperties(enabled, Duration.ofMinutes(2), Duration.ofSeconds(10), 100, pages,
                2 * 1024 * 1024, 16 * 1024 * 1024, 500, releaseIssues, 1000,
                5000, 8 * 1024 * 1024, 20, 500, 10000, Duration.ofMinutes(15), 50, assignedIssues);
    }

    private static void assertFailure(Code code, Runnable call) {
        assertThatExceptionOfType(TicketException.class).isThrownBy(call::run)
                .satisfies(failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static String issue(String id, String key, String summary, String state, boolean stateResolved,
                                Long resolvedAt, String type, String priority, List<String> fixVersionIds,
                                String assigneeValueJson) {
        String fixVal = "[" + fixVersionIds.stream().map(v -> "{\"id\":\"" + v + "\"}")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        String assigneePart = assigneeValueJson == null ? "null" : assigneeValueJson;
        String resolvedPart = resolvedAt == null ? "null" : resolvedAt.toString();
        return """
                {"id":"%s","idReadable":"%s","summary":"%s","created":1760000000000,"updated":1760090000000,"resolved":%s,
                 "project":{"id":"0-1","shortName":"YT"},
                 "customFields":[
                   {"name":"Type","$type":"SingleEnumIssueCustomField","value":{"name":"%s"}},
                   {"name":"State","$type":"StateIssueCustomField","value":{"name":"%s","isResolved":%s}},
                   {"name":"Priority","$type":"SingleEnumIssueCustomField","value":{"name":"%s"}},
                   {"name":"Fix versions","$type":"MultiVersionIssueCustomField","value":%s},
                   {"name":"Assignee","$type":"SingleUserIssueCustomField","value":%s}
                 ]}
                """.formatted(id, key, summary, resolvedPart, type, state, stateResolved, priority, fixVal, assigneePart);
    }

    private static String queryParam(HttpRequest request) {
        String rawQuery = request.uri().getRawQuery();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "query".equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private record Reply(int status, Map<String, List<String>> headers, String body) {
        private Reply(String body) {
            this(200, Map.of(), body);
        }
    }

    private static final class Fixture {
        private final List<HttpRequest> requests = new ArrayList<>();
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
                subscriber.onSubscribe(mock(Flow.Subscription.class));
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

        private YouTrackAdapter adapter(YouTrackProperties settings, TicketProperties limits) {
            return new YouTrackAdapter(YouTrackTestCredentials.credentials(settings), limits,
                    new YouTrackTransport(client, limits.maxResponseBytes(), CLOCK), MAPPER, CLOCK);
        }
    }

    private static final class Gate implements TicketRequestGate {
        @Override
        public Duration beforeRequest() {
            return Duration.ofSeconds(10);
        }

        @Override
        public Duration remaining() {
            return Duration.ofSeconds(120);
        }

        @Override
        public void receivedBytes(long count) { }

        @Override
        public void acceptedObjects(int count) { }

        @Override
        public void check() { }
    }
}
