package io.givops.ticketing.youtrack;

import static io.givops.ticketing.youtrack.YouTrackConnection.invalidConfiguration;
import static io.givops.ticketing.youtrack.YouTrackPayload.invalidResponse;
import static io.givops.ticketing.youtrack.YouTrackTransport.limitExceeded;

import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketBrowserModels.*;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketBrowseAdapter;
import io.givops.ticketing.port.TicketContentAdapter;
import io.givops.ticketing.port.TicketProviderAdapter.Access;
import io.givops.ticketing.port.TicketRequestGate;
import java.net.URI;
import java.net.URLEncoder;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Transient live browser. Description text never enters the observation acquisition path. */
public final class YouTrackBrowserAdapter implements TicketBrowseAdapter, TicketContentAdapter {
    private static final int PAGE_SIZE = 100;
    static final String FIELDS = "id,idReadable,summary,created,updated,resolved,isDraft,project(id,shortName),tags(name),"
            + "customFields(name,value(id,name,login,ringId,isResolved))";
    private static final BrowseDescriptor DESCRIPTOR = new BrowseDescriptor(
            List.of("text:key-title-literal-nfc", "statuses", "categories", "labels:any"),
            List.of(Lifecycle.ACTIVE), List.of("key-asc"), false, true, false);
    private final YouTrackCredentials credentials;
    private final TicketProperties limits;
    private final YouTrackTransport transport;
    private final ObjectReader json;

    public YouTrackBrowserAdapter(YouTrackCredentials credentials, TicketProperties limits,
                                 YouTrackTransport transport, JsonMapper mapper) {
        this.credentials = credentials;
        this.limits = limits;
        this.transport = transport;
        this.json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    @Override public TicketProvider provider() { return TicketProvider.YOUTRACK; }

    @Override public BrowseDescriptor describe(BrowserRequest request, Access access, TicketRequestGate gate) {
        var session = new Session(request, access, gate);
        session.check();
        return DESCRIPTOR;
    }

    @Override public TicketPage list(BrowserRequest request, BrowseQuery query, Access access, TicketRequestGate gate) {
        var session = new Session(request, access, gate);
        if (query == null || query.lifecycle() != Lifecycle.ACTIVE || !"key-asc".equals(query.sort())) throw invalidConfiguration();
        for (var family : List.of(query.statuses(), query.categories(), query.labels()))
            for (String value : family) if (!validText(value, 256) || value.isBlank()) throw invalidConfiguration();
        if (query.text().codePoints().anyMatch(Character::isISOControl)) throw invalidConfiguration();
        String fingerprint = queryFingerprint(session.revision, query);
        int offset = offset(query.continuation(), fingerprint);

        // Read all bounded metadata before filtering, so OR families and literal text have exact semantics.
        JsonNode project = session.fetch("/admin/projects/" + request.externalProjectId() + "?fields=id,shortName");
        session.acceptedObjects(1);
        if (!request.externalProjectId().equals(text(project, "id", 128, true))) throw invalidResponse();
        String projectKey = text(project, "shortName", 64, true);
        if (!request.projectKey().equals(projectKey)) throw invalidResponse();
        var items = new ArrayList<TicketSummary>();
        var identities = new HashSet<String>();
        var keys = new HashSet<String>();
        String yql = "project: {" + projectKey + "} sort by: {issue id} asc";
        boolean complete = false;
        for (int page = 0; page < limits.maxPages(); page++) {
            JsonNode root = session.fetch("/issues?fields=" + FIELDS + "&query="
                    + URLEncoder.encode(yql, StandardCharsets.UTF_8).replace("+", "%20")
                    + "&$top=" + PAGE_SIZE + "&$skip=" + page * PAGE_SIZE);
            YouTrackPayload.requireArray(root);
            if (root.size() > PAGE_SIZE) throw invalidResponse();
            session.acceptedObjects(root.size());
            for (JsonNode node : root) {
                session.check();
                if (YouTrackPayload.optionalBoolean(node, "isDraft", false)) {
                    session.validateIdentity(node, null);
                    continue;
                }
                var detail = session.detail(node, null);
                var summary = detail.ticket();
                if (!identities.add(summary.ref().externalIssueId()) || !keys.add(summary.displayKey())) throw invalidResponse();
                if (matches(summary, query)) items.add(summary);
            }
            if (root.size() < PAGE_SIZE) { complete = true; break; }
        }
        if (!complete) throw limitExceeded();
        items.sort(Comparator.comparing((TicketSummary item) -> new BigInteger(item.displayKey().substring(item.displayKey().lastIndexOf('-') + 1)))
                .thenComparing(item -> item.ref().externalIssueId()));
        if (offset > 0 && offset >= items.size()) throw stale();
        int end = Math.min(items.size(), offset + query.pageSize());
        String next = end < items.size() ? "v1." + end + "." + fingerprint : null;
        session.check();
        return new TicketPage(items.subList(offset, end), next, null, session.revision, request.capturedAt(), query);
    }

    @Override public TicketDetail detail(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        var session = new Session(request, access, gate);
        session.validateRef(ticket);
        JsonNode node = session.fetchIssue(ticket, FIELDS);
        session.validateIdentity(node, ticket.externalIssueId());
        if (YouTrackPayload.optionalBoolean(node, "isDraft", false)) throw notFound();
        var result = session.detail(node, ticket.externalIssueId());
        session.check();
        return result;
    }

    @Override public TicketContent content(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        var session = new Session(request, access, gate);
        session.validateRef(ticket);
        JsonNode node = session.fetchIssue(ticket, "id,project(id),isDraft,description");
        session.validateIdentity(node, ticket.externalIssueId());
        if (YouTrackPayload.optionalBoolean(node, "isDraft", false)) throw notFound();
        JsonNode description = node.get("description");
        String value = description == null || description.isNull() ? "" : description.isString() ? description.stringValue() : null;
        if (value == null) throw invalidResponse();
        if (value.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) throw limitExceeded();
        session.check();
        return new TicketContent(ticket, session.revision, ContentFormat.MARKDOWN, value, List.of(), List.of());
    }

    private final class Session implements TicketRequestGate {
        private final BrowserRequest request;
        private final YouTrackCredentials.Captured captured;
        private final TicketRequestGate gate;
        private final YouTrackConnection connection;
        private final SourceRevision revision;
        private long bytes;
        private int objects;
        private int attempts;

        Session(BrowserRequest request, Access access, TicketRequestGate gate) {
            if (!limits.enabled()) throw new TicketException(Code.DISABLED,
                    YouTrackMessages.format("ticketing.youTrackAdapter.ticketing.access.is.disabled"));
            this.request = request;
            this.gate = Objects.requireNonNull(gate);
            gate.check();
            if (request == null || request.source() == null || request.ticketProjectId() == null
                    || request.source().instanceId() == null || request.source().instanceKey() == null || request.source().baseUrl() == null
                    || request.source().provider() != TicketProvider.YOUTRACK
                    || request.capturedAt() == null || !entityId(request.externalProjectId())
                    || request.projectKey() == null || !YouTrackPayload.PROJECT_KEY.matcher(request.projectKey()).matches()) throw invalidConfiguration();
            if (!(access instanceof YouTrackCredentials.Captured yt)) throw superseded();
            this.captured = yt;
            this.connection = yt.connection();
            check();
            this.revision = new SourceRevision("youtrack-live", digest(List.of(request.source().instanceId().toString(),
                    request.ticketProjectId().toString(), Long.toString(request.source().revision()),
                    request.externalProjectId(), request.projectKey(), access.context())), false);
            if (request.revision() != null && !revision.equals(request.revision())) throw stale();
        }

        @Override public void check() {
            gate.check();
            credentials.requireCurrent(captured, false);
            if (!captured.context().equals(connection.context(request.source()))
                    || !connection.projectUrl("").equals(URI.create(YouTrackConnection.normalize(request.source().baseUrl()) + "/projects/"))) throw superseded();
        }
        @Override public Duration beforeRequest() {
            check();
            if (++attempts > limits.maxHttpAttempts()) throw limitExceeded();
            return gate.beforeRequest();
        }
        @Override public Duration remaining() { return gate.remaining(); }
        @Override public void receivedBytes(long count) {
            if (count < 0 || count > limits.maxTotalResponseBytes() - bytes) throw limitExceeded();
            bytes += count;
            gate.receivedBytes(count);
            check();
        }
        @Override public void acceptedObjects(int count) {
            if (count < 0 || count > limits.maxObjects() - objects) throw limitExceeded();
            objects += count;
            gate.acceptedObjects(count);
            check();
        }
        JsonNode fetch(String endpoint) {
            byte[] body = transport.read(connection.endpoint(endpoint), connection.authorization(), this);
            return parse(body);
        }
        JsonNode parse(byte[] body) {
            check();
            try { return json.readTree(body); }
            catch (JacksonException exception) { throw invalidResponse(); }
        }
        JsonNode fetchIssue(TicketRef ref, String fields) {
            byte[] body = transport.readOptional(connection.endpoint("/issues/" + ref.externalIssueId() + "?fields=" + fields),
                    connection.authorization(), this).orElseThrow(YouTrackBrowserAdapter::notFound);
            acceptedObjects(1);
            return parse(body);
        }
        void validateRef(TicketRef ref) {
            if (ref == null || !request.source().instanceId().equals(ref.instanceId())
                    || !request.externalProjectId().equals(ref.externalProjectId()) || !entityId(ref.externalIssueId())) throw invalidConfiguration();
        }
        String validateIdentity(JsonNode node, String expectedId) {
            YouTrackPayload.requireObject(node);
            String id = text(node, "id", 128, true);
            JsonNode project = YouTrackPayload.requireObject(node.get("project"));
            if (!entityId(id) || expectedId != null && !expectedId.equals(id)
                    || !request.externalProjectId().equals(text(project, "id", 128, true))) throw invalidResponse();
            return id;
        }
        TicketDetail detail(JsonNode node, String expectedId) {
            String id = validateIdentity(node, expectedId);
            String key = text(node, "idReadable", 1024, true);
            if (!YouTrackPayload.ISSUE_KEY.matcher(key).matches() || !key.startsWith(request.projectKey() + "-")) throw invalidResponse();
            String title = text(node, "summary", 4096, true);
            Instant created = timestamp(node, "created", true), updated = timestamp(node, "updated", true);
            Instant resolved = timestamp(node, "resolved", false);
            var labels = new LinkedHashSet<String>();
            JsonNode tags = node.get("tags");
            if (tags != null && !tags.isNull()) {
                YouTrackPayload.requireArray(tags);
                if (tags.size() > 100) throw limitExceeded();
                for (JsonNode tag : tags) labels.add(text(tag, "name", 256, true));
            }
            String status = null, category = null, assignee = null;
            Boolean stateResolved = null;
            var versions = new LinkedHashSet<String>();
            var names = new HashSet<String>();
            JsonNode fields = node.get("customFields");
            if (fields != null && !fields.isNull()) {
                YouTrackPayload.requireArray(fields);
                if (fields.size() > 1000) throw limitExceeded();
                for (JsonNode field : fields) {
                    String name = text(field, "name", 256, true).toLowerCase(Locale.ROOT);
                    if (!names.add(name)) throw invalidResponse();
                    JsonNode value = field.get("value");
                    if (value == null || value.isNull()) continue;
                    switch (name) {
                        case "state" -> {
                            status = text(value, "name", 256, true);
                            stateResolved = YouTrackPayload.optionalBoxedBoolean(value, "isResolved");
                        }
                        case "type" -> category = value.isString() ? scalar(value.stringValue(), 256) : text(value, "name", 256, true);
                        case "assignee" -> {
                            JsonNode user = value.isArray() ? value.size() == 1 ? value.get(0) : null : value;
                            if (user != null && !user.isNull()) {
                                assignee = text(user, "ringId", 1024, false);
                                if (assignee == null) assignee = text(user, "id", 1024, false);
                                if (assignee == null) assignee = text(user, "login", 1024, false);
                            }
                        }
                        case "fix versions", "fix version" -> {
                            if (value.isArray()) {
                                if (value.size() > 1000) throw limitExceeded();
                                for (JsonNode version : value) versions.add(text(version, "id", 1024, true));
                            } else versions.add(text(value, "id", 1024, true));
                        }
                        default -> { }
                    }
                }
            }
            if (versions.size() > 1000) throw limitExceeded();
            var ref = new TicketRef(request.source().instanceId(), request.externalProjectId(), id);
            var summary = new TicketSummary(ref, key, title, status, category, Lifecycle.ACTIVE,
                    outcome(status, stateResolved, resolved), List.copyOf(labels), connection.browse(key), updated, true, revision);
            return new TicketDetail(summary, created, assignee, List.copyOf(versions),
                    new Navigation(connection.projectUrl(request.externalProjectId()), connection.browse(key)));
        }
    }

    private static boolean matches(TicketSummary item, BrowseQuery query) {
        String needle = folded(query.text());
        return (needle.isEmpty() || folded(item.displayKey()).contains(needle) || folded(item.title()).contains(needle))
                && (query.statuses().isEmpty() || item.nativeStatus() != null && query.statuses().contains(item.nativeStatus()))
                && (query.categories().isEmpty() || item.category() != null && query.categories().contains(item.category()))
                && (query.labels().isEmpty() || query.labels().stream().anyMatch(item.labels()::contains));
    }
    private static String folded(String value) { return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    private static Outcome outcome(String status, Boolean resolved, Instant resolvedAt) {
        String state = status == null ? "" : status.strip().toLowerCase(Locale.ROOT);
        if (Boolean.TRUE.equals(resolved) || resolvedAt != null) {
            return switch (state) {
                case "cancelled", "canceled", "won't fix", "won’t fix", "duplicate", "obsolete" -> Outcome.CANCELLED;
                case "fixed", "done", "resolved", "verified" -> Outcome.SUCCEEDED;
                default -> Outcome.UNKNOWN;
            };
        }
        return switch (state) {
            case "open", "new", "submitted", "to do", "reopened" -> Outcome.OPEN;
            case "in progress" -> Outcome.IN_PROGRESS;
            default -> Outcome.UNKNOWN;
        };
    }
    private int offset(String continuation, String fingerprint) {
        if (continuation == null) return 0;
        String[] parts = continuation.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals("v1") || !parts[1].matches("[1-9][0-9]{0,8}")
                || !parts[2].equals(fingerprint)) throw invalidConfiguration();
        int offset = Integer.parseInt(parts[1]);
        if (offset > limits.maxObjects()) throw invalidConfiguration();
        return offset;
    }
    private static String queryFingerprint(SourceRevision revision, BrowseQuery query) {
        var values = new ArrayList<String>(List.of(revision.value(), query.text(), query.lifecycle().name(), query.sort(), Integer.toString(query.pageSize())));
        for (var family : List.of(query.statuses(), query.categories(), query.labels())) {
            values.add(Integer.toString(family.size()));
            values.addAll(family);
        }
        return digest(values);
    }
    private static String digest(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) digest.update((value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static boolean entityId(String value) { return value != null && YouTrackPayload.ENTITY_ID.matcher(value).matches(); }
    private static boolean validText(String value, int length) { return value != null && value.length() <= length && value.codePoints().noneMatch(Character::isISOControl); }
    private static String scalar(String value, int length) {
        if (!validText(value, length) || value.isBlank()) throw invalidResponse();
        return value;
    }
    private static String text(JsonNode node, String field, int length, boolean required) {
        YouTrackPayload.requireObject(node);
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) throw invalidResponse();
            return null;
        }
        if (!value.isString()) throw invalidResponse();
        return scalar(value.stringValue(), length);
    }
    private static Instant timestamp(JsonNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) throw invalidResponse();
            return null;
        }
        try {
            if (value.isIntegralNumber() && value.canConvertToLong()) return Instant.ofEpochMilli(value.longValue());
        } catch (DateTimeException exception) { throw invalidResponse(); }
        throw invalidResponse();
    }
    private static TicketException stale() { return new TicketException(Code.STALE_CONTEXT, YouTrackMessages.format("youtrack.browser.stale")); }
    private static TicketException notFound() { return new TicketException(Code.NOT_FOUND, YouTrackMessages.format("youtrack.browser.not-found")); }
    private static TicketException superseded() { return new TicketException(Code.SUPERSEDED, YouTrackMessages.format("ticketing.youTrackCredentials.the.youtrack.credential.context.changed")); }
}
