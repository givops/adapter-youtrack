package io.givops.ticketing.youtrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.givops.ticketing.model.TicketWriteModels.RemoteProjectRef;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.model.TicketWriteModels.CreateIssueCommand;
import io.givops.ticketing.model.TicketWriteModels.CreateProjectCommand;
import io.givops.ticketing.model.TicketWriteModels.CreateVersionCommand;
import io.givops.ticketing.model.TicketWriteModels.IssueKind;
import io.givops.ticketing.model.TicketWriteModels.PriorityLevel;
import io.givops.ticketing.model.TicketWriteModels.WorkflowState;
import io.givops.ticketing.port.TicketRequestGate;
import java.net.URI;
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
import java.util.UUID;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class YouTrackWriteAdapterTest {
    private static final URI BASE_URL = URI.create("https://team.youtrack.cloud");
    private static final String TOKEN = "perm-synthetic-token";
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TicketProperties LIMITS = limits(true);
    private static final InstanceSource SOURCE = new InstanceSource(
            UUID.fromString("2972b7b5-03cf-4058-a4a2-17a2a6c3897b"), "yt", TicketProvider.YOUTRACK, BASE_URL, 0);
    private static final RemoteProjectRef CATALOG_PROJECT = new RemoteProjectRef(
            UUID.fromString("b76eaf5d-645f-4b50-a8aa-70b46af7aa24"),
            SOURCE.instanceId(), "0-12", "GVO", "GivOps Orders", 0);

    private static final String CUSTOM_FIELDS_JSON = """
            [
              {"id":"189-0","$type":"EnumProjectCustomField","canBeEmpty":false,
               "field":{"id":"161-0","name":"Priority","fieldType":{"id":"enum[1]","valueType":"enum"}},
               "bundle":{"id":"163-0","$type":"EnumBundle","values":[
                 {"id":"164-1","name":"Critical"},{"id":"164-2","name":"Major"},{"id":"164-3","name":"Normal"}]}},
              {"id":"189-1","$type":"EnumProjectCustomField","canBeEmpty":false,
               "field":{"id":"161-1","name":"Type","fieldType":{"id":"enum[1]","valueType":"enum"}},
               "bundle":{"id":"163-1","$type":"EnumBundle","values":[
                 {"id":"164-5","name":"Bug"},{"id":"164-8","name":"Feature"},{"id":"164-9","name":"Task"}]}},
              {"id":"189-2","$type":"StateProjectCustomField","canBeEmpty":false,
               "field":{"id":"161-2","name":"State","fieldType":{"id":"state[1]","valueType":"state"}},
               "bundle":{"id":"165-1","$type":"StateBundle","values":[
                 {"id":"166-12","name":"To do","isResolved":false},
                 {"id":"166-13","name":"In Progress","isResolved":false},
                 {"id":"166-14","name":"Done","isResolved":true}]}},
              {"id":"189-4","$type":"VersionProjectCustomField","canBeEmpty":true,
               "field":{"id":"161-5","name":"Fix versions","fieldType":{"id":"version[*]","valueType":"version"}},
               "bundle":{"id":"168-5","$type":"VersionBundle","values":[
                 {"id":"170-1","name":"1.0.0"},{"id":"170-2","name":"1.1.0"}]}},
              {"id":"190-0","$type":"UserProjectCustomField","canBeEmpty":true,
               "field":{"id":"161-3","name":"Assignee","fieldType":{"id":"user[1]","valueType":"user"}},
               "bundle":{"id":"13-0","$type":"UserBundle","individuals":[],"aggregatedUsers":[{"id":"2-1","login":"admin","ringId":"ring-admin"}]}}
            ]
            """;

    @Test
    void createsBlankProjectWithIsolatedFixVersionBundle() throws Exception {
        var fixture = new Fixture(
                new Reply("{\"id\":\"2-1\",\"login\":\"admin\",\"ringId\":\"ring-admin\",\"guest\":false}"),
                new Reply("{\"id\":\"0-12\",\"shortName\":\"GVO\",\"name\":\"GivOps Orders\"}"),
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply("{\"id\":\"168-99\",\"name\":\"GivOps Orders (GVO) Fix Versions\"}"),
                new Reply("{\"id\":\"189-4\",\"bundle\":{\"id\":\"168-99\"}}"));

        var adapter = fixture.adapter();
        var access = adapter.captureAccess(SOURCE);
        var created = adapter.createProject(SOURCE,
                new CreateProjectCommand("GVO", "GivOps Orders", "Blank E2E project", null),
                access, new Gate());

        assertThat(created.externalProjectId()).isEqualTo("0-12");
        assertThat(created.projectKey()).isEqualTo("GVO");
        assertThat(created.displayName()).isEqualTo("GivOps Orders");
        assertThat(created.webUrl()).isEqualTo(URI.create("https://team.youtrack.cloud/projects/0-12"));

        assertThat(fixture.requests).hasSize(5);
        assertThat(fixture.requests.get(0).method()).isEqualTo("GET");
        assertThat(fixture.requests.get(1).uri().getPath()).isEqualTo("/api/admin/projects");
        assertThat(MAPPER.readTree(fixture.bodies.get(1)).path("shortName").asString()).isEqualTo("GVO");
        assertThat(fixture.requests.get(3).uri().getPath()).isEqualTo("/api/admin/customFieldSettings/bundles/version");
        assertThat(fixture.requests.get(4).uri().getPath()).isEqualTo("/api/admin/projects/0-12/customFields/189-4");
        assertThat(MAPPER.readTree(fixture.bodies.get(4)).path("bundle").path("id").asString()).isEqualTo("168-99");
    }

    @Test
    void createsVersionWithUtcReleaseDateEpochMillis() throws Exception {
        LocalDate date = LocalDate.of(2026, 11, 20);
        long expectedMillis = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        var fixture = new Fixture(
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply("{\"id\":\"170-9\",\"name\":\"1.1.0\",\"released\":true,\"archived\":false,\"releaseDate\":" + expectedMillis + "}"));

        var adapter = fixture.adapter();
        var version = adapter.createVersion(SOURCE, CATALOG_PROJECT,
                new CreateVersionCommand("1.1.0", true, false, date),
                adapter.captureAccess(SOURCE), new Gate());

        assertThat(version.externalVersionId()).isEqualTo("170-9");
        assertThat(version.name()).isEqualTo("1.1.0");
        assertThat(version.released()).isTrue();
        assertThat(version.releaseDate()).isEqualTo(date);

        var posted = MAPPER.readTree(fixture.bodies.get(1));
        assertThat(fixture.requests.get(1).uri().getPath())
                .isEqualTo("/api/admin/customFieldSettings/bundles/version/168-5/values");
        assertThat(posted.path("releaseDate").asLong()).isEqualTo(expectedMillis);
    }

    @Test
    void createsIssueMappingAgnosticEnumsFixVersionsAndAssignee() throws Exception {
        String createdIssueJson = """
                {"id":"2-42","idReadable":"GVO-1","summary":"Enforce idempotency",
                 "created":1763200000000,"updated":1763200000000,"resolved":null,
                 "project":{"id":"0-12","shortName":"GVO"},
                 "customFields":[
                   {"name":"Type","$type":"SingleEnumIssueCustomField","value":{"id":"164-8","name":"Feature"}},
                   {"name":"State","$type":"StateIssueCustomField","value":{"id":"166-13","name":"In Progress","isResolved":false}},
                   {"name":"Priority","$type":"SingleEnumIssueCustomField","value":{"id":"164-1","name":"Critical"}},
                   {"name":"Fix versions","$type":"MultiVersionIssueCustomField","value":[{"id":"170-2","name":"1.1.0"}]},
                   {"name":"Assignee","$type":"SingleUserIssueCustomField","value":{"id":"2-1","login":"admin","ringId":"ring-admin"}}
                 ]}
                """;
        var fixture = new Fixture(
                new Reply(CUSTOM_FIELDS_JSON),
                new Reply("{\"id\":\"2-1\",\"login\":\"admin\",\"ringId\":\"ring-admin\",\"guest\":false}"),
                new Reply(200, createdIssueJson));

        var adapter = fixture.adapter();
        var issue = adapter.createIssue(SOURCE, CATALOG_PROJECT,
                new CreateIssueCommand("Enforce idempotency", "Body", IssueKind.FEATURE, null,
                        WorkflowState.IN_PROGRESS, null, PriorityLevel.CRITICAL, null,
                        List.of("1.1.0"), true, null),
                adapter.captureAccess(SOURCE), new Gate());

        assertThat(issue.issueKey()).isEqualTo("GVO-1");
        assertThat(issue.type()).isEqualTo("Feature");
        assertThat(issue.status()).isEqualTo("In Progress");
        assertThat(issue.statusCategory()).isEqualTo("indeterminate");
        assertThat(issue.priority()).isEqualTo("Critical");
        assertThat(issue.releaseVersionIds()).containsExactly("170-2");
        assertThat(issue.assigneeAccountId()).isEqualTo("ring-admin");
        assertThat(issue.webUrl()).isEqualTo(URI.create("https://team.youtrack.cloud/issue/GVO-1"));

        var posted = MAPPER.readTree(fixture.bodies.get(2));
        assertThat(posted.path("project").path("id").asString()).isEqualTo("0-12");
        assertThat(posted.path("customFields")).hasSize(5);
    }

    @Test void typedVersionIdResolvesInsideTheBoundBundleWhileLegacyStringsRemainNames() {
        var fixture=new Fixture(new Reply(CUSTOM_FIELDS_JSON.replace("1.0.0", "1.1.0")),new Reply(200,"""
                {"id":"2-77","idReadable":"GVO-1","summary":"Typed version","created":1794736800000,"updated":1794736800000,
                 "project":{"id":"0-12","shortName":"GVO"},"customFields":[
                  {"name":"Fix versions","$type":"MultiVersionIssueCustomField","value":[{"id":"170-2","name":"1.1.0"}]}]}
                """));
        var adapter=fixture.adapter();
        var command=new CreateIssueCommand("Typed version",null,null,null,null,null,null,null,List.of(),false,null,
                List.of(new io.givops.ticketing.model.TicketWriteModels.VersionSelector(io.givops.ticketing.model.TicketWriteModels.VersionSelectorKind.EXTERNAL_ID,"170-2")));
        assertThat(adapter.createIssue(SOURCE,CATALOG_PROJECT,command,adapter.captureAccess(SOURCE),new Gate()).releaseVersionIds()).containsExactly("170-2");
        var posted=MAPPER.readTree(fixture.bodies.get(1));
        assertThat(posted.path("customFields").get(0).path("value").get(0).path("id").asString()).isEqualTo("170-2");
        var legacy=new Fixture(new Reply(CUSTOM_FIELDS_JSON));var legacyAdapter=legacy.adapter();
        var legacyCommand=new CreateIssueCommand("Legacy numeric name",null,null,null,null,null,null,null,List.of("170-2"),false,null);
        assertThatExceptionOfType(TicketException.class).isThrownBy(() -> legacyAdapter.createIssue(SOURCE,CATALOG_PROJECT,legacyCommand,legacyAdapter.captureAccess(SOURCE),new Gate()));
        assertThat(legacy.bodies).hasSize(1);
    }
    @Test void refusesMissingProgressStateWithoutCreatingAnOpenIssue() {
        var fixture=new Fixture(new Reply(CUSTOM_FIELDS_JSON.replace("In Progress","Unclassified")));
        var adapter=fixture.adapter();
        var command=new CreateIssueCommand("Progress",null,null,null,WorkflowState.IN_PROGRESS,null,null,null,List.of(),false,null);
        assertThatExceptionOfType(TicketException.class).isThrownBy(() -> adapter.createIssue(SOURCE,CATALOG_PROJECT,command,adapter.captureAccess(SOURCE),new Gate()));
        assertThat(fixture.bodies).hasSize(1);
    }

    @Test
    void doesNotLeakProviderErrorBodyAndRejectsDisabledWrites() {
        var secretSentinel = "sensitive-upstream-error-body";
        var failing = new Fixture(
                new Reply("{\"id\":\"2-1\",\"login\":\"admin\",\"guest\":false}"),
                new Reply(400, "{\"error\":\"" + secretSentinel + "\"}"));

        var adapter = failing.adapter();
        assertThatExceptionOfType(TicketException.class)
                .isThrownBy(() -> adapter.createProject(SOURCE,
                        new CreateProjectCommand("GVO", "GivOps Orders", null, null),
                        adapter.captureAccess(SOURCE), new Gate()))
                .satisfies(ex -> {
                    assertThat(ex.code()).isEqualTo(Code.INVALID_CONFIGURATION);
                    assertThat(ex.getMessage()).doesNotContain(secretSentinel);
                });

        var disabledAdapter = failing.adapter(limits(false));
        assertThatExceptionOfType(TicketException.class)
                .isThrownBy(() -> disabledAdapter.createProject(SOURCE,
                        new CreateProjectCommand("GVO", "GivOps Orders", null, null),
                        disabledAdapter.captureAccess(SOURCE), new Gate()))
                .satisfies(ex -> assertThat(ex.code()).isEqualTo(Code.DISABLED));
    }

    private static TicketProperties limits(boolean enabled) {
        return new TicketProperties(enabled, Duration.ofMinutes(2), Duration.ofSeconds(10), 100, 20,
                2 * 1024 * 1024, 16 * 1024 * 1024, 500, 500, 1000,
                5000, 8 * 1024 * 1024, 20, 500, 10000, Duration.ofMinutes(15), 50, 500);
    }

    private record Reply(int status, String body) {
        private Reply(String body) {
            this(200, body);
        }
    }

    private static final class Fixture {
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private final HttpClient client = mock(HttpClient.class);

        private Fixture(Reply... replies) {
            var remaining = new ArrayDeque<>(List.of(replies));
            when(client.<byte[]>sendAsync(any(), any())).thenAnswer(invocation -> {
                HttpRequest req = invocation.getArgument(0);
                requests.add(req);
                bodies.add(extractRequestBody(req));
                HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                Reply reply = remaining.remove();
                var headers = HttpHeaders.of(Map.of(), (k, v) -> true);
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

        private YouTrackWriteAdapter adapter() {
            return adapter(LIMITS);
        }

        private YouTrackWriteAdapter adapter(TicketProperties limits) {
            var props = new YouTrackProperties(
                    Map.of("yt", new YouTrackProperties.Instance(BASE_URL.toString(), TOKEN, "1")));
            return new YouTrackWriteAdapter(YouTrackTestCredentials.credentials(props), limits,
                    new YouTrackTransport(client, limits.maxResponseBytes(), CLOCK),
                    new YouTrackWriteTransport(client, limits.maxResponseBytes(), CLOCK),
                    MAPPER, CLOCK);
        }

        private static String extractRequestBody(HttpRequest request) {
            if (request.bodyPublisher().isEmpty()) {
                return "";
            }
            var captured = new ArrayList<ByteBuffer>();
            request.bodyPublisher().get().subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(ByteBuffer item) { captured.add(item); }
                @Override public void onError(Throwable throwable) { }
                @Override public void onComplete() { }
            });
            int total = captured.stream().mapToInt(ByteBuffer::remaining).sum();
            byte[] all = new byte[total];
            int pos = 0;
            for (ByteBuffer buf : captured) {
                int len = buf.remaining();
                buf.get(all, pos, len);
                pos += len;
            }
            return new String(all, StandardCharsets.UTF_8);
        }
    }

    private static final class Gate implements TicketRequestGate {
        @Override public Duration beforeRequest() { return Duration.ofSeconds(10); }
        @Override public Duration remaining() { return Duration.ofSeconds(120); }
        @Override public void receivedBytes(long count) { }
        @Override public void acceptedObjects(int count) { }
        @Override public void check() { }
    }
}
