package io.givops.ticketing.youtrack;

import static io.givops.ticketing.youtrack.YouTrackConnection.invalidConfiguration;
import static io.givops.ticketing.youtrack.YouTrackPayload.invalidResponse;

import io.givops.ticketing.model.TicketWriteModels.RemoteProjectRef;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.IssueObservation;
import io.givops.ticketing.model.TicketModels.ProjectObservation;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.model.TicketModels.VersionObservation;
import io.givops.ticketing.model.TicketWriteModels.CreateIssueCommand;
import io.givops.ticketing.model.TicketWriteModels.CreateProjectCommand;
import io.givops.ticketing.model.TicketWriteModels.CreateVersionCommand;
import io.givops.ticketing.model.TicketWriteModels.IssueKind;
import io.givops.ticketing.model.TicketWriteModels.PriorityLevel;
import io.givops.ticketing.model.TicketWriteModels.WorkflowState;
import io.givops.ticketing.port.TicketProviderAdapter.Access;
import io.givops.ticketing.port.TicketRequestGate;
import io.givops.ticketing.port.TicketWriteAdapter;
import io.givops.ticketing.youtrack.YouTrackPayload.CustomFieldSchema;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

public class YouTrackWriteAdapter implements TicketWriteAdapter {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_LABEL_LENGTH = 200;
    private static final int MAX_VERSION_LENGTH = 255;
    private static final int MAX_SUMMARY_LENGTH = 512;
    private static final int MAX_PROJECT_DESCRIPTION_LENGTH = 4000;
    private static final int MAX_ISSUE_DESCRIPTION_LENGTH = 16384;
    private static final int MAX_FIX_VERSIONS = 20;
    private static final String USER_FIELDS = "id,login,ringId,guest";
    private static final String DETAILED_CUSTOM_FIELD_FIELDS =
            "id,$type,canBeEmpty,field(id,name,fieldType(id,valueType)),"
                    + "bundle(id,$type,values(id,name,isResolved),individuals(id,login,ringId),aggregatedUsers(id,login,ringId))";

    private final YouTrackCredentials credentials;
    private final TicketProperties limits;
    private final YouTrackTransport readTransport;
    private final YouTrackWriteTransport writeTransport;
    private final JsonMapper mapper;
    private final ObjectReader reader;
    private final Clock clock;

    public YouTrackWriteAdapter(YouTrackCredentials credentials, TicketProperties limits, YouTrackTransport readTransport,
                         YouTrackWriteTransport writeTransport, JsonMapper mapper, Clock clock) {
        this.credentials = credentials;
        this.limits = limits;
        this.readTransport = readTransport;
        this.writeTransport = writeTransport;
        this.mapper = mapper;
        this.reader = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        this.clock = clock;
    }

    @Override
    public TicketProvider provider() {
        return TicketProvider.YOUTRACK;
    }

    @Override
    public Access captureAccess(InstanceSource source) {
        return credentials.capture(source);
    }

    @Override
    public void requireCurrent(InstanceSource source, Access captured, boolean locked) {
        if (!(captured instanceof YouTrackCredentials.Captured yt)) {
            throw new TicketException(Code.SUPERSEDED, YouTrackMessages.format("ticketing.youTrackWriteAdapter.the.youtrack.credential.context.changed"));
        }
        credentials.requireCurrent(yt, locked);
    }

    @Override
    public ProjectObservation createProject(InstanceSource source, CreateProjectCommand command, Access captured,
                                            TicketRequestGate gate) {
        requireEnabled();
        gate.check();
        if (command == null) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.project.creation.parameters.are.required"));
        }
        String projectKey = validateProjectKey(command.projectKey());
        String displayName = validateSingleLine(command.displayName(), MAX_LABEL_LENGTH, YouTrackMessages.format("ticketing.youTrackWriteAdapter.label.project.display.name"));
        String description = validateMultilineOptional(command.description(), MAX_PROJECT_DESCRIPTION_LENGTH,
                YouTrackMessages.format("ticketing.youTrackWriteAdapter.label.project.description"));
        String explicitLeader = validateOptionalIdentifier(command.leaderExternalId(), YouTrackMessages.format("ticketing.youTrackWriteAdapter.label.leader.id"));
        if (explicitLeader != null && !YouTrackPayload.ENTITY_ID.matcher(explicitLeader).matches()) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.the.youtrack.leader.id.must.be.a.valid.entity.identifier"));
        }

        YouTrackConnection connection = resolveConnection(source, captured);
        String leaderId = explicitLeader != null ? explicitLeader : resolveCurrentUserId(connection, gate);

        var body = new LinkedHashMap<String, Object>();
        body.put("name", displayName);
        body.put("shortName", projectKey);
        body.put("leader", Map.of("id", leaderId));
        if (description != null) {
            body.put("description", description);
        }

        URI createUri = connection.endpoint("/admin/projects?fields=" + YouTrackAdapter.PROJECT_FIELDS);
        JsonNode created = parseJson(writeTransport.postJson(createUri, connection.authorization(),
                toJsonBytes(body), gate));
        gate.acceptedObjects(1);
        YouTrackPayload.requireObject(created);
        String projectId = YouTrackPayload.requireEntityId(created, "id");
        YouTrackPayload.ProjectDetails details = YouTrackPayload.project(
                created, projectId, connection, clock.instant());
        if (!projectKey.equals(details.shortName())) {
            throw invalidResponse();
        }

        isolateFixVersionBundle(connection, projectId, projectKey, displayName, gate);
        gate.check();
        return details.observation();
    }

    @Override
    public VersionObservation createVersion(InstanceSource source, RemoteProjectRef project,
                                            CreateVersionCommand command, Access captured, TicketRequestGate gate) {
        requireEnabled();
        gate.check();
        validateProjectRef(project);
        if (command == null) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.version.creation.parameters.are.required"));
        }
        String name = validateVersionName(command.name());

        YouTrackConnection connection = resolveConnection(source, captured);
        List<DetailedField> fields = readDetailedCustomFields(connection, project.externalProjectId(), gate).fields();
        DetailedField fixVersionField = findFixVersionField(fields);
        if (fixVersionField == null || fixVersionField.bundleId() == null) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.the.youtrack.project.does.not.have.a.fix.versions.bundle.configured"));
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("$type", "VersionBundleElement");
        body.put("name", name);
        body.put("released", command.released());
        body.put("archived", command.archived());
        if (command.releaseDate() != null) {
            body.put("releaseDate", command.releaseDate().atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli());
        }

        URI uri = connection.endpoint("/admin/customFieldSettings/bundles/version/"
                + encodePath(fixVersionField.bundleId()) + "/values?fields=" + YouTrackAdapter.VERSION_FIELDS);
        JsonNode created = parseJson(writeTransport.postJson(uri, connection.authorization(),
                toJsonBytes(body), gate));
        gate.acceptedObjects(1);
        gate.check();
        return YouTrackPayload.version(created, clock.instant());
    }

    @Override
    public IssueObservation createIssue(InstanceSource source, RemoteProjectRef project,
                                        CreateIssueCommand command, Access captured, TicketRequestGate gate) {
        requireEnabled();
        gate.check();
        validateProjectRef(project);
        if (command == null) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.issue.creation.parameters.are.required"));
        }
        String summary = validateSingleLine(command.summary(), MAX_SUMMARY_LENGTH, YouTrackMessages.format("ticketing.youTrackWriteAdapter.label.issue.summary"));
        String description = validateMultilineOptional(command.description(), MAX_ISSUE_DESCRIPTION_LENGTH,
                YouTrackMessages.format("ticketing.youTrackWriteAdapter.label.issue.description"));
        List<String> fixVersions = validateFixVersions(command.fixVersions());
        String explicitAssignee = validateOptionalIdentifier(command.assigneeExternalId(), YouTrackMessages.format("ticketing.youTrackWriteAdapter.label.assignee.id"));

        YouTrackConnection connection = resolveConnection(source, captured);
        FieldCatalog fieldCatalog = readDetailedCustomFields(connection, project.externalProjectId(), gate);
        List<Map<String, Object>> customFields = new ArrayList<>();

        addEnumFieldIfRequested(customFields, findFieldByName(fieldCatalog.fields(), "Type"),
                trimToNull(command.providerType()), typeCandidates(command.type()), YouTrackMessages.format("ticketing.youTrackWriteAdapter.label.type"));
        addStateFieldIfRequested(customFields, findFieldByName(fieldCatalog.fields(), "State"),
                trimToNull(command.providerStatus()), command.workflowState());
        addEnumFieldIfRequested(customFields, findFieldByName(fieldCatalog.fields(), "Priority"),
                trimToNull(command.providerPriority()), priorityCandidates(command.priority()), YouTrackMessages.format("ticketing.youTrackWriteAdapter.label.priority"));
        addFixVersionsIfRequested(customFields, findFixVersionField(fieldCatalog.fields()), fixVersions);
        addAssigneeIfRequested(connection, gate, customFields, findAssigneeField(fieldCatalog.fields()),
                command.assignToSelf(), explicitAssignee);

        var body = new LinkedHashMap<String, Object>();
        body.put("project", Map.of("id", project.externalProjectId()));
        body.put("summary", summary);
        if (description != null) {
            body.put("description", description);
        }
        if (!customFields.isEmpty()) {
            body.put("customFields", customFields);
        }

        URI uri = connection.endpoint("/issues?fields=" + YouTrackAdapter.ISSUE_FIELDS);
        JsonNode created = parseJson(writeTransport.postJson(uri, connection.authorization(),
                toJsonBytes(body), gate));
        gate.acceptedObjects(1);
        CustomFieldSchema schema = YouTrackPayload.customFields(fieldCatalog.rawNodes());
        gate.check();
        return YouTrackPayload.issue(created, project.externalProjectId(), schema, connection, clock.instant());
    }

    private void isolateFixVersionBundle(YouTrackConnection connection, String projectId, String projectKey,
                                         String displayName, TicketRequestGate gate) {
        List<DetailedField> fields = readDetailedCustomFields(connection, projectId, gate).fields();
        DetailedField fixVersionField = findFixVersionField(fields);
        if (fixVersionField == null) {
            return;
        }
        String suffix = " (" + projectKey + ") Fix Versions";
        int maxPrefix = Math.max(16, MAX_LABEL_LENGTH - suffix.length());
        String prefix = displayName.length() <= maxPrefix ? displayName : displayName.substring(0, maxPrefix);
        String bundleName = prefix + suffix;
        if (bundleName.length() > MAX_LABEL_LENGTH) {
            bundleName = bundleName.substring(0, MAX_LABEL_LENGTH);
        }

        URI createBundleUri = connection.endpoint("/admin/customFieldSettings/bundles/version?fields=id,name");
        JsonNode bundle = parseJson(writeTransport.postJson(createBundleUri, connection.authorization(),
                toJsonBytes(Map.of("name", bundleName)), gate));
        gate.acceptedObjects(1);
        YouTrackPayload.requireObject(bundle);
        String bundleId = YouTrackPayload.requireId(bundle, "id");

        URI attachUri = connection.endpoint("/admin/projects/" + encodePath(projectId)
                + "/customFields/" + encodePath(fixVersionField.projectCustomFieldId()) + "?fields=id,bundle(id)");
        Map<String, Object> attachBody = Map.of(
                "$type", "VersionProjectCustomField",
                "bundle", Map.of("$type", "VersionBundle", "id", bundleId));
        writeTransport.postJson(attachUri, connection.authorization(), toJsonBytes(attachBody), gate);
    }

    private String resolveCurrentUserId(YouTrackConnection connection, TicketRequestGate gate) {
        JsonNode me = readUser(connection, "/users/me?fields=" + USER_FIELDS, gate);
        return YouTrackPayload.requireEntityId(me, "id");
    }

    private JsonNode readUser(YouTrackConnection connection, String path, TicketRequestGate gate) {
        byte[] body = readTransport.read(connection.endpoint(path), connection.authorization(), gate);
        JsonNode node = parseJson(body);
        gate.acceptedObjects(1);
        YouTrackPayload.requireObject(node);
        if (YouTrackPayload.optionalBoolean(node, "guest", false)) {
            throw new TicketException(Code.AUTHORIZATION_FAILED,
                    YouTrackMessages.format("ticketing.youTrackWriteAdapter.the.youtrack.guest.user.cannot.be.used.for.write.operations"));
        }
        return node;
    }

    private FieldCatalog readDetailedCustomFields(YouTrackConnection connection, String projectId,
                                                  TicketRequestGate gate) {
        var rawNodes = new ArrayList<JsonNode>();
        var fields = new ArrayList<DetailedField>();
        String base = "/admin/projects/" + encodePath(projectId)
                + "/customFields?fields=" + DETAILED_CUSTOM_FIELD_FIELDS;
        for (int page = 0; page < limits.maxPages(); page++) {
            int skip = page * PAGE_SIZE;
            URI uri = connection.endpoint(base + "&$top=" + PAGE_SIZE + "&$skip=" + skip);
            JsonNode root = YouTrackPayload.requireArray(parseJson(
                    readTransport.read(uri, connection.authorization(), gate)));
            gate.acceptedObjects(root.size());
            for (JsonNode item : root) {
                rawNodes.add(item);
                fields.add(parseDetailedField(item));
            }
            if (root.size() < PAGE_SIZE) {
                return new FieldCatalog(List.copyOf(rawNodes), List.copyOf(fields));
            }
        }
        throw YouTrackTransport.limitExceeded();
    }

    private DetailedField parseDetailedField(JsonNode item) {
        YouTrackPayload.requireObject(item);
        String projectCustomFieldId = YouTrackPayload.requireId(item, "id");
        String itemType = YouTrackPayload.optionalText(item, "$type", 128);
        JsonNode field = YouTrackPayload.requireObject(YouTrackPayload.child(item, "field"));
        String fieldName = YouTrackPayload.requireText(field, "name", 255);
        JsonNode fieldType = YouTrackPayload.child(field, "fieldType");
        String fieldTypeId = fieldType == null || fieldType.isNull()
                ? null : YouTrackPayload.optionalText(YouTrackPayload.requireObject(fieldType), "id", 128);
        String valueType = fieldType == null || fieldType.isNull()
                ? null : YouTrackPayload.optionalText(YouTrackPayload.requireObject(fieldType), "valueType", 128);

        String bundleId = null;
        String bundleType = null;
        var values = new ArrayList<BundleValue>();
        var userIds = new HashSet<String>();
        JsonNode bundle = YouTrackPayload.child(item, "bundle");
        if (bundle != null && !bundle.isNull()) {
            YouTrackPayload.requireObject(bundle);
            bundleId = YouTrackPayload.optionalId(bundle, "id");
            bundleType = YouTrackPayload.optionalText(bundle, "$type", 128);
            JsonNode valuesNode = YouTrackPayload.child(bundle, "values");
            if (valuesNode != null && !valuesNode.isNull()) {
                YouTrackPayload.requireArray(valuesNode);
                for (JsonNode valueNode : valuesNode) {
                    YouTrackPayload.requireObject(valueNode);
                    String valueId = YouTrackPayload.optionalId(valueNode, "id");
                    String valueName = YouTrackPayload.optionalText(valueNode, "name", 255);
                    Boolean isResolved = YouTrackPayload.optionalBoxedBoolean(valueNode, "isResolved");
                    if (valueId != null && valueName != null) {
                        values.add(new BundleValue(valueId, valueName, isResolved));
                    }
                }
            }
            collectUserIds(YouTrackPayload.child(bundle, "individuals"), userIds);
            collectUserIds(YouTrackPayload.child(bundle, "aggregatedUsers"), userIds);
        }
        return new DetailedField(projectCustomFieldId, itemType, fieldName, fieldTypeId, valueType,
                bundleId, bundleType, List.copyOf(values), Set.copyOf(userIds));
    }

    private static void collectUserIds(JsonNode arrayNode, Set<String> out) {
        if (arrayNode == null || arrayNode.isNull()) {
            return;
        }
        YouTrackPayload.requireArray(arrayNode);
        for (JsonNode user : arrayNode) {
            YouTrackPayload.requireObject(user);
            String id = YouTrackPayload.optionalId(user, "id");
            if (id != null) {
                out.add(id);
            }
        }
    }

    private void addEnumFieldIfRequested(List<Map<String, Object>> customFields, DetailedField field,
                                         String explicitName, List<String> candidates, String label) {
        if (explicitName == null && candidates.isEmpty()) {
            return;
        }
        if (field == null) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.missing.custom.field", Map.of("label", label)));
        }
        BundleValue selected = explicitName != null
                ? findValueByNames(field.bundleValues(), List.of(explicitName))
                : findValueByNames(field.bundleValues(), candidates);
        if (selected == null) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.missing.bundle.value", Map.of("label", label)));
        }
        boolean multi = isMultiField(field.fieldTypeId());
        var element = Map.of("$type", "EnumBundleElement", "id", selected.id());
        customFields.add(Map.of(
                "$type", multi ? "MultiEnumIssueCustomField" : "SingleEnumIssueCustomField",
                "name", field.fieldName(),
                "value", multi ? List.of(element) : element));
    }

    private void addStateFieldIfRequested(List<Map<String, Object>> customFields, DetailedField field,
                                          String explicitStatus, WorkflowState workflowState) {
        if (explicitStatus == null && workflowState == null) {
            return;
        }
        if (field == null) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.the.youtrack.project.does.not.configure.a.state.custom.field"));
        }
        BundleValue selected;
        if (explicitStatus != null) {
            selected = findValueByNames(field.bundleValues(), List.of(explicitStatus));
        } else {
            selected = selectWorkflowState(field.bundleValues(), workflowState);
        }
        if (selected == null) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.no.matching.youtrack.workflow.state.was.found"));
        }
        customFields.add(Map.of(
                "$type", "StateIssueCustomField",
                "name", field.fieldName(),
                "value", Map.of("$type", "StateBundleElement", "id", selected.id())));
    }

    private static BundleValue selectWorkflowState(List<BundleValue> values, WorkflowState workflowState) {
        if (workflowState == WorkflowState.DONE) {
            List<BundleValue> resolved = values.stream()
                    .filter(value -> Boolean.TRUE.equals(value.isResolved()))
                    .toList();
            BundleValue preferred = findValueByNames(resolved, List.of("Done", "Fixed", "Resolved", "Closed", "Verified"));
            return preferred != null ? preferred : (resolved.isEmpty() ? null : resolved.getFirst());
        }
        List<BundleValue> openValues = values.stream()
                .filter(value -> !Boolean.TRUE.equals(value.isResolved()))
                .toList();
        if (workflowState == WorkflowState.IN_PROGRESS) {
            BundleValue preferred = findValueByNames(openValues, List.of("In Progress", "Active", "Working", "Doing"));
            if (preferred != null) {
                return preferred;
            }
            if (openValues.size() > 1) {
                return openValues.get(1);
            }
            return openValues.isEmpty() ? null : openValues.getFirst();
        }
        BundleValue preferred = findValueByNames(openValues, List.of("To do", "Open", "Submitted", "New"));
        return preferred != null ? preferred : (openValues.isEmpty() ? null : openValues.getFirst());
    }

    private void addFixVersionsIfRequested(List<Map<String, Object>> customFields, DetailedField field,
                                           List<String> requestedVersions) {
        if (requestedVersions.isEmpty()) {
            return;
        }
        if (field == null) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.the.youtrack.project.does.not.configure.a.fix.versions.field"));
        }
        boolean multi = isMultiField(field.fieldTypeId())
                || (field.fieldTypeId() == null && "Fix versions".equalsIgnoreCase(field.fieldName()));
        if (!multi && requestedVersions.size() > 1) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.the.youtrack.fix.version.field.only.accepts.a.single.version"));
        }
        var elements = new ArrayList<Map<String, String>>(requestedVersions.size());
        for (String versionName : requestedVersions) {
            List<BundleValue> matches = field.bundleValues().stream()
                    .filter(value -> value.name().equals(versionName))
                    .toList();
            if (matches.size() != 1) {
                throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.version.not.unique", Map.of("versionName", versionName)));
            }
            elements.add(Map.of("$type", "VersionBundleElement", "id", matches.getFirst().id()));
        }
        customFields.add(Map.of(
                "$type", multi ? "MultiVersionIssueCustomField" : "SingleVersionIssueCustomField",
                "name", field.fieldName(),
                "value", multi ? elements : elements.getFirst()));
    }

    private void addAssigneeIfRequested(YouTrackConnection connection, TicketRequestGate gate,
                                        List<Map<String, Object>> customFields, DetailedField field,
                                        boolean assignToSelf, String explicitAssignee) {
        if (!assignToSelf && explicitAssignee == null) {
            return;
        }
        if (field == null) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.the.youtrack.project.does.not.configure.an.assignee.field"));
        }
        String path = explicitAssignee != null
                ? "/users/" + encodePath(explicitAssignee) + "?fields=" + USER_FIELDS
                : "/users/me?fields=" + USER_FIELDS;
        JsonNode user = readUser(connection, path, gate);
        String userId = YouTrackPayload.requireEntityId(user, "id");
        if (!field.bundleUserIds().contains(userId) && field.bundleId() != null) {
            URI addUserUri = connection.endpoint("/admin/customFieldSettings/bundles/user/"
                    + encodePath(field.bundleId()) + "/individuals?fields=id");
            writeTransport.postJson(addUserUri, connection.authorization(),
                    toJsonBytes(Map.of("$type", "User", "id", userId)), gate);
        }
        boolean multi = isMultiField(field.fieldTypeId());
        var ref = Map.of("$type", "User", "id", userId);
        customFields.add(Map.of(
                "$type", multi ? "MultiUserIssueCustomField" : "SingleUserIssueCustomField",
                "name", field.fieldName(),
                "value", multi ? List.of(ref) : ref));
    }

    private static List<String> typeCandidates(IssueKind kind) {
        if (kind == null) {
            return List.of();
        }
        return switch (kind) {
            case BUG -> List.of("Bug");
            case FEATURE -> List.of("Feature", "Story", "User Story");
            case TASK -> List.of("Task");
            case EPIC -> List.of("Epic");
        };
    }

    private static List<String> priorityCandidates(PriorityLevel level) {
        if (level == null) {
            return List.of();
        }
        return switch (level) {
            case CRITICAL -> List.of("Critical", "Show-stopper", "Highest");
            case HIGH -> List.of("Major", "High");
            case NORMAL -> List.of("Normal", "Medium");
            case LOW -> List.of("Minor", "Low", "Lowest");
        };
    }

    private static BundleValue findValueByNames(List<BundleValue> values, List<String> candidates) {
        for (String candidate : candidates) {
            for (BundleValue value : values) {
                if (value.name().equalsIgnoreCase(candidate)) {
                    return value;
                }
            }
        }
        return null;
    }

    private static DetailedField findFieldByName(List<DetailedField> fields, String name) {
        for (DetailedField field : fields) {
            if (field.fieldName().equalsIgnoreCase(name)) {
                return field;
            }
        }
        return null;
    }

    private static DetailedField findFixVersionField(List<DetailedField> fields) {
        DetailedField match = null;
        boolean exactPlural = false;
        for (DetailedField field : fields) {
            boolean isVersion = "VersionProjectCustomField".equals(field.itemType())
                    || isTypedValue(field.valueType(), "version")
                    || "VersionBundle".equals(field.bundleType());
            if (isVersion && ("Fix versions".equalsIgnoreCase(field.fieldName())
                    || "Fix version".equalsIgnoreCase(field.fieldName()))) {
                boolean plural = "Fix versions".equalsIgnoreCase(field.fieldName());
                if (match == null || (!exactPlural && plural)) {
                    match = field;
                    exactPlural = plural;
                }
            }
        }
        return match;
    }

    private static DetailedField findAssigneeField(List<DetailedField> fields) {
        for (DetailedField field : fields) {
            boolean isUser = "UserProjectCustomField".equals(field.itemType())
                    || isTypedValue(field.valueType(), "user")
                    || "UserBundle".equals(field.bundleType());
            if (isUser && "Assignee".equalsIgnoreCase(field.fieldName())) {
                return field;
            }
        }
        return null;
    }

    private static boolean isTypedValue(String valueType, String prefix) {
        return valueType != null && (valueType.equals(prefix) || valueType.startsWith(prefix + "["));
    }

    private static boolean isMultiField(String fieldTypeId) {
        return fieldTypeId != null && fieldTypeId.endsWith("[*]");
    }

    private void requireEnabled() {
        if (!limits.enabled()) {
            throw new TicketException(Code.DISABLED, YouTrackMessages.format("ticketing.youTrackWriteAdapter.ticketing.access.is.disabled"));
        }
    }

    private YouTrackConnection resolveConnection(InstanceSource source, Access captured) {
        if (captured instanceof YouTrackCredentials.Captured yt) {
            return yt.connection();
        }
        if (captured != null) {
            throw new TicketException(Code.SUPERSEDED, YouTrackMessages.format("ticketing.youTrackWriteAdapter.the.youtrack.credential.context.changed"));
        }
        return credentials.connection(source);
    }

    private static void validateProjectRef(RemoteProjectRef project) {
        if (project == null || project.externalProjectId() == null
                || !YouTrackPayload.ENTITY_ID.matcher(project.externalProjectId()).matches()) {
            throw invalidConfiguration();
        }
    }

    private static String validateProjectKey(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null || trimmed.length() < 2 || trimmed.length() > 64
                || !YouTrackPayload.PROJECT_KEY.matcher(trimmed).matches()
                || !YouTrackPayload.validQueryToken(trimmed)) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.the.project.key.must.start.with.an.uppercase.letter.followed.by.uppercase"));
        }
        return trimmed;
    }

    private static String validateVersionName(String value) {
        String trimmed = validateSingleLine(value, MAX_VERSION_LENGTH, YouTrackMessages.format("ticketing.youTrackWriteAdapter.label.version.name"));
        if (!YouTrackPayload.validQueryToken(trimmed)) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.the.version.name.contains.unsupported.braces.or.control.characters"));
        }
        return trimmed;
    }

    private static List<String> validateFixVersions(List<String> versions) {
        if (versions == null || versions.isEmpty()) {
            return List.of();
        }
        if (versions.size() > MAX_FIX_VERSIONS) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.too.many.fix.versions", Map.of("maximum", Integer.toString(MAX_FIX_VERSIONS))));
        }
        var result = new ArrayList<String>(versions.size());
        for (String version : versions) {
            result.add(validateVersionName(version));
        }
        return List.copyOf(result);
    }

    private static String validateSingleLine(String value, int maxLength, String label) {
        String trimmed = trimToNull(value);
        if (trimmed == null || trimmed.length() > maxLength
                || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.invalid.single.line", Map.of("label", label, "maximum", Integer.toString(maxLength))));
        }
        return trimmed;
    }

    private static String validateMultilineOptional(String value, int maxLength, String label) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.length() > maxLength || trimmed.codePoints()
                .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.invalid.multiline", Map.of("label", label, "maximum", Integer.toString(maxLength))));
        }
        return trimmed;
    }

    private static String validateOptionalIdentifier(String value, String label) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.length() > 128 || trimmed.contains("/") || trimmed.contains("..")
                || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidCommand(YouTrackMessages.format("ticketing.youTrackWriteAdapter.invalid.identifier", Map.of("label", label)));
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private byte[] toJsonBytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JacksonException exception) {
            throw new TicketException(Code.INTERNAL_ERROR, YouTrackMessages.format("ticketing.youTrackWriteAdapter.could.not.encode.the.youtrack.request.payload"));
        }
    }

    private JsonNode parseJson(byte[] body) {
        try {
            return reader.readTree(body);
        } catch (JacksonException exception) {
            throw invalidResponse();
        }
    }

    private static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static TicketException invalidCommand(String message) {
        return new TicketException(Code.INVALID_CONFIGURATION, message);
    }

    private record BundleValue(String id, String name, Boolean isResolved) { }

    private record DetailedField(String projectCustomFieldId, String itemType, String fieldName, String fieldTypeId,
                                 String valueType, String bundleId, String bundleType,
                                 List<BundleValue> bundleValues, Set<String> bundleUserIds) { }

    private record FieldCatalog(List<JsonNode> rawNodes, List<DetailedField> fields) { }


}
