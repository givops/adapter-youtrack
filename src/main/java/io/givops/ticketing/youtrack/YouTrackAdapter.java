package io.givops.ticketing.youtrack;

import static io.givops.ticketing.youtrack.YouTrackConnection.invalidConfiguration;
import static io.givops.ticketing.youtrack.YouTrackPayload.invalidResponse;
import static io.givops.ticketing.youtrack.YouTrackTransport.limitExceeded;

import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.IssueIdentity;
import io.givops.ticketing.model.TicketModels.IssueObservation;
import io.givops.ticketing.model.TicketModels.ProjectQuery;
import io.givops.ticketing.model.TicketModels.ProjectResult;
import io.givops.ticketing.model.TicketModels.ProviderInput;
import io.givops.ticketing.model.TicketModels.ProviderSnapshot;
import io.givops.ticketing.model.TicketModels.ReleaseMappingState;
import io.givops.ticketing.model.TicketModels.ReleaseMembership;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.model.TicketModels.VersionObservation;
import io.givops.ticketing.port.TicketProviderAdapter;
import io.givops.ticketing.port.TicketRequestGate;
import io.givops.ticketing.youtrack.YouTrackPayload.CustomFieldSchema;
import io.givops.ticketing.youtrack.YouTrackPayload.ProjectDetails;
import io.givops.ticketing.youtrack.YouTrackTransport.QueryResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

public class YouTrackAdapter implements TicketProviderAdapter {
    private static final int PAGE_SIZE = 100;
    private static final int KEY_BATCH_SIZE = 50;
    static final String PROJECT_FIELDS = "id,shortName,name";
    static final String CUSTOM_FIELD_FIELDS =
            "id,$type,field(id,name,fieldType(id,valueType)),bundle(id,$type)";
    static final String VERSION_FIELDS = "id,name,released,archived,releaseDate";
    static final String ISSUE_FIELDS =
            "id,idReadable,summary,created,updated,resolved,project(id,shortName),"
                    + "customFields(id,$type,name,value(id,name,login,ringId,fullName,isResolved,$type))";

    private final YouTrackCredentials credentials;
    private final TicketProperties limits;
    private final YouTrackTransport transport;
    private final ObjectReader json;
    private final Clock clock;

    public YouTrackAdapter(YouTrackCredentials credentials, TicketProperties limits, YouTrackTransport transport,
                    JsonMapper mapper, Clock clock) {
        this.credentials = credentials;
        this.limits = limits;
        this.transport = transport;
        this.json = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        this.clock = clock;
    }

    @Override
    public TicketProvider provider() {
        return TicketProvider.YOUTRACK;
    }

    @Override
    public String authenticationContext(InstanceSource source) {
        return captureAccess(source).context();
    }

    @Override
    public Access captureAccess(InstanceSource source) {
        return credentials.capture(source);
    }

    @Override
    public boolean current(InstanceSource source, Access captured, boolean locked) {
        if (!(captured instanceof YouTrackCredentials.Captured yt)) { return false; }
        credentials.requireCurrent(yt, locked);
        return true;
    }

    @Override
    public ProviderSnapshot read(ProviderInput input, TicketRequestGate gate) {
        return read(input, gate, null);
    }

    @Override
    public ProviderSnapshot read(ProviderInput input, TicketRequestGate gate, Access captured) {
        if (!limits.enabled()) {
            throw new TicketException(Code.DISABLED, YouTrackMessages.format("ticketing.youTrackAdapter.ticketing.access.is.disabled"));
        }
        gate.check();
        validateQueries(input.projects());
        var connection = resolveConnection(input.source(), captured);
        var session = new ReadSession(connection, gate);
        var results = new ArrayList<ProjectResult>(input.projects().size());
        for (ProjectQuery query : input.projects()) {
            results.add(session.readProject(query));
        }
        gate.check();
        return new ProviderSnapshot(results);
    }

    @Override
    public VersionMemberships readVersionMemberships(InstanceSource source, ProjectQuery query, Access captured,
                                                     TicketRequestGate gate,
                                                     Function<List<VersionObservation>, Set<String>> select,
                                                     int maximumVersions, int maximumIssues) {
        if (!limits.enabled()) {
            throw new TicketException(Code.DISABLED, YouTrackMessages.format("ticketing.youTrackAdapter.ticketing.access.is.disabled"));
        }
        if (maximumVersions < 0 || maximumIssues < 0) {
            throw invalidConfiguration();
        }
        gate.check();
        validateQueries(List.of(query));
        var connection = resolveConnection(source, captured);
        var session = new ReadSession(connection, gate);
        var project = session.readProjectDetails(query.externalProjectId());
        var schema = session.readCustomFields(query.externalProjectId());
        var versions = schema.fixVersionField() == null || schema.fixVersionField().bundleId() == null
                ? List.<VersionObservation>of()
                : session.readVersions(schema.fixVersionField().bundleId());

        Set<String> selected = Set.copyOf(select.apply(versions));
        if (selected.size() > maximumVersions) {
            throw limitExceeded();
        }
        var validIds = versions.stream().map(VersionObservation::externalVersionId).collect(Collectors.toSet());
        if (!validIds.containsAll(selected)) {
            throw invalidConfiguration();
        }

        var versionById = new LinkedHashMap<String, VersionObservation>(versions.size());
        for (VersionObservation version : versions) {
            versionById.put(version.externalVersionId(), version);
        }

        var members = new LinkedHashMap<String, List<IssueObservation>>();
        var unique = new IssueAccumulator();
        var memberships = new HashMap<IssueIdentity, Set<String>>();
        int remaining = Math.min(maximumIssues, limits.maxReleaseIssues());

        for (String versionId : selected.stream().sorted().toList()) {
            VersionObservation version = versionById.get(versionId);
            if (version == null || schema.fixVersionField() == null
                    || !YouTrackPayload.validQueryToken(version.name())) {
                throw invalidResponse();
            }
            String yql = "project: {" + project.shortName() + "} {"
                    + schema.fixVersionField().name() + "}: {" + version.name() + "} sort by: {issue id} asc";
            List<IssueObservation> queried = session.queryIssues(query.externalProjectId(), schema, yql, remaining + 1);
            var matched = new ArrayList<IssueObservation>(queried.size());
            for (IssueObservation issue : queried) {
                if (issue.releaseVersionIds().contains(versionId)) {
                    remaining--;
                    if (remaining < 0) {
                        throw limitExceeded();
                    }
                    unique.add(issue);
                    matched.add(issue);
                    memberships.computeIfAbsent(issue.identity(), ignored -> new HashSet<>()).add(versionId);
                }
            }
            members.put(versionId, List.copyOf(matched));
        }

        for (List<IssueObservation> list : members.values()) {
            for (IssueObservation issue : list) {
                Set<String> expected = issue.releaseVersionIds().stream()
                        .filter(selected::contains)
                        .collect(Collectors.toSet());
                if (!expected.equals(memberships.get(issue.identity()))) {
                    throw invalidResponse();
                }
            }
        }
        gate.check();
        return new VersionMemberships(members);
    }

    private YouTrackConnection resolveConnection(InstanceSource source, Access captured) {
        if (captured instanceof YouTrackCredentials.Captured yt) {
            return yt.connection();
        }
        if (captured != null) {
            throw new TicketException(Code.SUPERSEDED, YouTrackMessages.format("ticketing.youTrackCredentials.the.youtrack.credential.context.changed"));
        }
        return credentials.connection(source);
    }

    private void validateQueries(List<ProjectQuery> projects) {
        if (projects.size() > limits.maxBindings()) {
            throw limitExceeded();
        }
        long keyCount = 0;
        for (ProjectQuery query : projects) {
            if (query == null || query.ticketProjectId() == null
                    || !validPathId(query.externalProjectId())
                    || !YouTrackPayload.PROJECT_KEY.matcher(query.projectKey()).matches()) {
                throw invalidConfiguration();
            }
            keyCount += query.issueKeys().size();
            if (keyCount > limits.maxIssueKeys()) {
                throw limitExceeded();
            }
        }
    }

    private static boolean validPathId(String value) {
        return value != null && YouTrackPayload.ENTITY_ID.matcher(value).matches();
    }

    private static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }



    private static final class IssueAccumulator {
        private final Map<IssueIdentity, IssueObservation> byId = new LinkedHashMap<>();
        private final Map<String, IssueIdentity> keys = new HashMap<>();

        void add(IssueObservation issue) {
            String normalizedKey = issue.issueKey().toUpperCase(Locale.ROOT);
            IssueIdentity existingKeyOwner = keys.get(normalizedKey);
            if (existingKeyOwner != null && !existingKeyOwner.equals(issue.identity())) {
                throw invalidResponse();
            }
            keys.put(normalizedKey, issue.identity());
            byId.put(issue.identity(), issue);
        }

        List<IssueObservation> values() {
            return List.copyOf(byId.values());
        }
    }

    private final class ReadSession {
        private final YouTrackConnection connection;
        private final TicketRequestGate gate;
        private int remainingReleaseIssues = limits.maxReleaseIssues();
        private int remainingAssignedIssues = limits.maxAssignedIssues();

        private ReadSession(YouTrackConnection connection, TicketRequestGate gate) {
            this.connection = connection;
            this.gate = gate;
        }

        ProjectResult readProject(ProjectQuery query) {
            ProjectDetails project = readProjectDetails(query.externalProjectId());
            CustomFieldSchema schema = readCustomFields(query.externalProjectId());
            List<VersionObservation> versions =
                    schema.fixVersionField() == null || schema.fixVersionField().bundleId() == null
                            ? List.of()
                            : readVersions(schema.fixVersionField().bundleId());

            var issues = new IssueAccumulator();
            var unresolved = new LinkedHashSet<>(query.issueKeys());
            readRequestedIssues(query, project, schema, issues);

            ReleaseMembership release = readReleaseMembership(query, project, schema, versions, issues);
            List<IssueIdentity> assigned = readAssignedIssues(query, project, schema, issues);

            List<IssueObservation> observedIssues = issues.values();
            var foundKeys = observedIssues.stream()
                    .map(issue -> issue.issueKey().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            unresolved.removeIf(key -> foundKeys.contains(key.toUpperCase(Locale.ROOT)));

            return new ProjectResult(query.ticketProjectId(), project.observation(), versions, observedIssues,
                    unresolved, release, assigned);
        }

        ProjectDetails readProjectDetails(String externalProjectId) {
            URI uri = connection.endpoint("/admin/projects/" + encodePath(externalProjectId)
                    + "?fields=" + PROJECT_FIELDS);
            JsonNode root = fetchJson(uri);
            gate.acceptedObjects(1);
            return YouTrackPayload.project(root, externalProjectId, connection, clock.instant());
        }

        CustomFieldSchema readCustomFields(String externalProjectId) {
            var items = new ArrayList<JsonNode>();
            String base = "/admin/projects/" + encodePath(externalProjectId)
                    + "/customFields?fields=" + CUSTOM_FIELD_FIELDS;
            for (int page = 0; page < limits.maxPages(); page++) {
                int skip = page * PAGE_SIZE;
                URI uri = connection.endpoint(base + "&$top=" + PAGE_SIZE + "&$skip=" + skip);
                JsonNode root = YouTrackPayload.requireArray(fetchJson(uri));
                gate.acceptedObjects(root.size());
                for (JsonNode node : root) {
                    items.add(node);
                }
                if (root.size() < PAGE_SIZE) {
                    return YouTrackPayload.customFields(items);
                }
            }
            throw limitExceeded();
        }

        List<VersionObservation> readVersions(String bundleId) {
            var versions = new ArrayList<VersionObservation>();
            var seenIds = new HashSet<String>();
            Instant observedAt = clock.instant();
            String base = "/admin/customFieldSettings/bundles/version/" + encodePath(bundleId)
                    + "/values?fields=" + VERSION_FIELDS;
            for (int page = 0; page < limits.maxPages(); page++) {
                int skip = page * PAGE_SIZE;
                URI uri = connection.endpoint(base + "&$top=" + PAGE_SIZE + "&$skip=" + skip);
                JsonNode root = YouTrackPayload.requireArray(fetchJson(uri));
                gate.acceptedObjects(root.size());
                versions.addAll(YouTrackPayload.versions(root, observedAt, seenIds));
                if (root.size() < PAGE_SIZE) {
                    return List.copyOf(versions);
                }
            }
            throw limitExceeded();
        }

        private void readRequestedIssues(ProjectQuery query, ProjectDetails project, CustomFieldSchema schema,
                                         IssueAccumulator issues) {
            var keys = query.issueKeys().stream()
                    .filter(YouTrackPayload::validQueryToken)
                    .filter(key -> matchesProjectPrefix(key, project.shortName()))
                    .sorted()
                    .toList();
            for (int offset = 0; offset < keys.size(); offset += KEY_BATCH_SIZE) {
                var batch = keys.subList(offset, Math.min(keys.size(), offset + KEY_BATCH_SIZE));
                resolveKeyBatch(batch, query.externalProjectId(), project, schema, issues);
            }
        }

        private void resolveKeyBatch(List<String> batch, String externalProjectId, ProjectDetails project,
                                     CustomFieldSchema schema, IssueAccumulator issues) {
            var batchUpper = batch.stream()
                    .map(key -> key.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            String yql = "project: {" + project.shortName() + "} {issue id}: "
                    + String.join(", ", batch) + " sort by: {issue id} asc";
            Optional<List<IssueObservation>> queried = tryQueryIssues(externalProjectId, schema, yql, PAGE_SIZE);
            if (queried.isPresent()) {
                for (IssueObservation issue : queried.get()) {
                    if (batchUpper.contains(issue.issueKey().toUpperCase(Locale.ROOT))) {
                        issues.add(issue);
                    }
                }
                return;
            }
            if (batch.size() > 4) {
                int mid = batch.size() / 2;
                resolveKeyBatch(batch.subList(0, mid), externalProjectId, project, schema, issues);
                resolveKeyBatch(batch.subList(mid, batch.size()), externalProjectId, project, schema, issues);
                return;
            }
            Instant observedAt = clock.instant();
            for (String key : batch) {
                URI uri = connection.endpoint("/issues/" + encodePath(key) + "?fields=" + ISSUE_FIELDS);
                Optional<byte[]> raw = transport.readOptional(uri, connection.authorization(), gate);
                if (raw.isEmpty()) {
                    continue;
                }
                JsonNode node = parseJson(raw.get());
                gate.acceptedObjects(1);
                Optional<IssueObservation> parsed = YouTrackPayload.issueIfInProject(
                        node, externalProjectId, schema, connection, observedAt);
                if (parsed.isPresent()
                        && parsed.get().issueKey().toUpperCase(Locale.ROOT).equals(key.toUpperCase(Locale.ROOT))) {
                    issues.add(parsed.get());
                }
            }
        }

        private ReleaseMembership readReleaseMembership(ProjectQuery query, ProjectDetails project,
                                                        CustomFieldSchema schema, List<VersionObservation> versions,
                                                        IssueAccumulator issues) {
            String releaseName = query.releaseVersionName();
            if (releaseName == null || releaseName.isBlank()) {
                return new ReleaseMembership(ReleaseMappingState.NOT_REQUESTED, null, List.of(), null);
            }
            if (schema.fixVersionField() == null) {
                return new ReleaseMembership(ReleaseMappingState.UNSUPPORTED, null, List.of(), clock.instant());
            }
            var matches = versions.stream()
                    .filter(version -> version.name().equals(releaseName))
                    .limit(2)
                    .toList();
            if (matches.size() != 1) {
                var state = matches.isEmpty() ? ReleaseMappingState.NOT_FOUND : ReleaseMappingState.AMBIGUOUS;
                return new ReleaseMembership(state, null, List.of(), clock.instant());
            }
            VersionObservation target = matches.getFirst();
            if (!YouTrackPayload.validQueryToken(target.name())) {
                throw invalidResponse();
            }
            String yql = "project: {" + project.shortName() + "} {"
                    + schema.fixVersionField().name() + "}: {" + target.name() + "} sort by: {issue id} asc";
            List<IssueObservation> queried = queryIssues(query.externalProjectId(), schema, yql,
                    remainingReleaseIssues + 1);
            var identities = new LinkedHashSet<IssueIdentity>();
            for (IssueObservation issue : queried) {
                if (issue.releaseVersionIds().contains(target.externalVersionId())) {
                    if (identities.add(issue.identity())) {
                        remainingReleaseIssues--;
                        if (remainingReleaseIssues < 0) {
                            throw limitExceeded();
                        }
                        issues.add(issue);
                    }
                }
            }
            return new ReleaseMembership(ReleaseMappingState.MATCHED, target.externalVersionId(),
                    List.copyOf(identities), clock.instant());
        }

        private List<IssueIdentity> readAssignedIssues(ProjectQuery query, ProjectDetails project,
                                                         CustomFieldSchema schema, IssueAccumulator issues) {
            if (schema.assigneeFieldName() == null) {
                return List.of();
            }
            String yql = "project: {" + project.shortName() + "} has: {"
                    + schema.assigneeFieldName() + "} #Unresolved sort by: {issue id} asc";
            List<IssueObservation> queried = queryIssues(query.externalProjectId(), schema, yql,
                    remainingAssignedIssues + 1);
            var identities = new LinkedHashSet<IssueIdentity>();
            for (IssueObservation issue : queried) {
                if (validAssignee(issue.assigneeAccountId())
                        && !"done".equalsIgnoreCase(issue.statusCategory())) {
                    if (identities.add(issue.identity())) {
                        remainingAssignedIssues--;
                        if (remainingAssignedIssues < 0) {
                            throw limitExceeded();
                        }
                        issues.add(issue);
                    }
                }
            }
            return List.copyOf(identities);
        }

        List<IssueObservation> queryIssues(String externalProjectId, CustomFieldSchema schema,
                                           String yql, int maxMatched) {
            return tryQueryIssues(externalProjectId, schema, yql, maxMatched)
                    .orElseThrow(() -> new TicketException(Code.PROVIDER_ERROR, YouTrackMessages.format("ticketing.youTrackAdapter.youtrack.rejected.the.api.request")));
        }

        private Optional<List<IssueObservation>> tryQueryIssues(String externalProjectId, CustomFieldSchema schema,
                                                                String yql, int maxMatched) {
            var collected = new ArrayList<IssueObservation>();
            Instant observedAt = clock.instant();
            String base = "/issues?fields=" + ISSUE_FIELDS + "&query=" + encodeQuery(yql);
            for (int page = 0; page < limits.maxPages(); page++) {
                int remaining = Math.max(0, maxMatched - collected.size());
                int top = Math.max(1, Math.min(PAGE_SIZE, remaining));
                int skip = collected.size();
                URI uri = connection.endpoint(base + "&$top=" + top + "&$skip=" + skip);
                QueryResponse response = transport.readQuery(uri, connection.authorization(), gate);
                if (response instanceof QueryResponse.Rejected) {
                    return Optional.empty();
                }
                JsonNode root = YouTrackPayload.requireArray(parseJson(((QueryResponse.Ok) response).body()));
                gate.acceptedObjects(root.size());
                List<IssueObservation> parsed = YouTrackPayload.issues(
                        root, externalProjectId, schema, connection, observedAt);
                collected.addAll(parsed);
                if (root.size() < top || collected.size() >= maxMatched) {
                    return Optional.of(collected);
                }
            }
            throw limitExceeded();
        }

        private JsonNode fetchJson(URI uri) {
            return parseJson(transport.read(uri, connection.authorization(), gate));
        }

        private JsonNode parseJson(byte[] body) {
            try {
                return json.readTree(body);
            } catch (JacksonException exception) {
                throw invalidResponse();
            }
        }

        private static boolean matchesProjectPrefix(String issueKey, String projectShortName) {
            int dash = issueKey.indexOf('-');
            return dash > 0 && issueKey.substring(0, dash).equalsIgnoreCase(projectShortName);
        }

        private static boolean validAssignee(String accountId) {
            return accountId != null && !accountId.isBlank() && accountId.length() <= 128
                    && accountId.codePoints().noneMatch(Character::isISOControl);
        }
    }
}
