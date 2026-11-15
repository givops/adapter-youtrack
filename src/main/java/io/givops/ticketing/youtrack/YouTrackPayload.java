package io.givops.ticketing.youtrack;

import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.IssueIdentity;
import io.givops.ticketing.model.TicketModels.IssueObservation;
import io.givops.ticketing.model.TicketModels.ProjectObservation;
import io.givops.ticketing.model.TicketModels.VersionObservation;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

final class YouTrackPayload {
    static final Pattern ENTITY_ID = Pattern.compile("[0-9]{1,18}(?:-[0-9]{1,18})?");
    static final Pattern PROJECT_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    static final Pattern ISSUE_KEY = Pattern.compile(PROJECT_KEY.pattern() + "-[1-9][0-9]{0,18}");
    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_LABEL_LENGTH = 255;
    private static final int MAX_SUMMARY_LENGTH = 512;

    private YouTrackPayload() { }

    record ProjectDetails(ProjectObservation observation, String shortName) { }

    record FixVersionField(String name, String bundleId) { }

    record CustomFieldSchema(FixVersionField fixVersionField, String assigneeFieldName) { }

    static ProjectDetails project(JsonNode root, String expectedProjectId, YouTrackConnection connection,
                                  Instant observedAt) {
        requireObject(root);
        String id = requireEntityId(root, "id");
        if (!id.equals(expectedProjectId)) {
            throw invalidResponse();
        }
        String shortName = requireText(root, "shortName", 64);
        if (!PROJECT_KEY.matcher(shortName).matches() || !validQueryToken(shortName)) {
            throw invalidResponse();
        }
        String name = optionalText(root, "name", MAX_LABEL_LENGTH);
        String displayName = name == null || name.isBlank() ? shortName : name;
        return new ProjectDetails(
                new ProjectObservation(id, shortName, displayName, connection.projectUrl(id), observedAt),
                shortName);
    }

    static CustomFieldSchema customFields(List<JsonNode> items) {
        FixVersionField fixVersionField = null;
        boolean exactFixVersions = false;
        String assigneeFieldName = null;
        for (JsonNode item : items) {
            requireObject(item);
            JsonNode field = requireObject(child(item, "field"));
            String fieldName = requireText(field, "name", MAX_LABEL_LENGTH);
            String itemType = optionalText(item, "$type", MAX_ID_LENGTH);
            JsonNode fieldType = child(field, "fieldType");
            String valueType = fieldType == null || fieldType.isNull()
                    ? null : optionalText(requireObject(fieldType), "valueType", MAX_ID_LENGTH);
            JsonNode bundle = child(item, "bundle");
            String bundleId = null;
            String bundleType = null;
            if (bundle != null && !bundle.isNull()) {
                requireObject(bundle);
                bundleId = optionalId(bundle, "id");
                bundleType = optionalText(bundle, "$type", MAX_ID_LENGTH);
            }
            boolean isVersionField = "VersionProjectCustomField".equals(itemType)
                    || isTypedValue(valueType, "version")
                    || "VersionBundle".equals(bundleType);
            if (isVersionField && ("Fix versions".equalsIgnoreCase(fieldName)
                    || "Fix version".equalsIgnoreCase(fieldName))) {
                if (!validQueryToken(fieldName)) {
                    throw invalidResponse();
                }
                boolean isPlural = "Fix versions".equalsIgnoreCase(fieldName);
                if (fixVersionField == null || (!exactFixVersions && isPlural)) {
                    fixVersionField = new FixVersionField(fieldName, bundleId);
                    exactFixVersions = isPlural;
                }
            }
            boolean isUserField = "UserProjectCustomField".equals(itemType)
                    || isTypedValue(valueType, "user")
                    || "UserBundle".equals(bundleType);
            if (isUserField && "Assignee".equalsIgnoreCase(fieldName)) {
                if (!validQueryToken(fieldName)) {
                    throw invalidResponse();
                }
                if (assigneeFieldName == null) {
                    assigneeFieldName = fieldName;
                }
            }
        }
        return new CustomFieldSchema(fixVersionField, assigneeFieldName);
    }

    static List<VersionObservation> versions(JsonNode page, Instant observedAt, Set<String> seenIds) {
        requireArray(page);
        var result = new ArrayList<VersionObservation>(page.size());
        for (JsonNode node : page) {
            VersionObservation parsed = version(node, observedAt);
            if (!seenIds.add(parsed.externalVersionId())) {
                throw invalidResponse();
            }
            result.add(parsed);
        }
        return result;
    }

    static VersionObservation version(JsonNode node, Instant observedAt) {
        requireObject(node);
        String id = requireId(node, "id");
        String name = requireText(node, "name", MAX_LABEL_LENGTH);
        boolean released = optionalBoolean(node, "released", false);
        boolean archived = optionalBoolean(node, "archived", false);
        LocalDate releaseDate = parseReleaseDate(child(node, "releaseDate"));
        return new VersionObservation(id, name, released, archived, releaseDate, observedAt);
    }

    static List<IssueObservation> issues(JsonNode page, String expectedProjectId, CustomFieldSchema schema,
                                         YouTrackConnection connection, Instant observedAt) {
        requireArray(page);
        var result = new ArrayList<IssueObservation>(page.size());
        for (JsonNode node : page) {
            result.add(issue(node, expectedProjectId, schema, connection, observedAt));
        }
        return result;
    }

    static IssueObservation issue(JsonNode node, String expectedProjectId, CustomFieldSchema schema,
                                  YouTrackConnection connection, Instant observedAt) {
        return parseIssue(node, expectedProjectId, schema, connection, observedAt, true)
                .orElseThrow(YouTrackPayload::invalidResponse);
    }

    static Optional<IssueObservation> issueIfInProject(JsonNode node, String expectedProjectId, CustomFieldSchema schema,
                                                       YouTrackConnection connection, Instant observedAt) {
        return parseIssue(node, expectedProjectId, schema, connection, observedAt, false);
    }

    private static Optional<IssueObservation> parseIssue(JsonNode node, String expectedProjectId,
                                                         CustomFieldSchema schema, YouTrackConnection connection,
                                                         Instant observedAt, boolean requireExpectedProject) {
        requireObject(node);
        String id = requireEntityId(node, "id");
        String issueKey = requireText(node, "idReadable", MAX_ID_LENGTH);
        if (!ISSUE_KEY.matcher(issueKey).matches()) {
            throw invalidResponse();
        }
        JsonNode projectNode = requireObject(child(node, "project"));
        String issueProjectId = requireEntityId(projectNode, "id");
        if (!expectedProjectId.equals(issueProjectId)) {
            if (requireExpectedProject) {
                throw invalidResponse();
            }
            return Optional.empty();
        }
        String summary = requireText(node, "summary", MAX_SUMMARY_LENGTH);
        Instant createdAt = requireTimestamp(node, "created");
        Instant updatedAt = optionalTimestamp(node, "updated");
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
        Instant resolvedAt = optionalTimestamp(node, "resolved");

        String type = null;
        String status = null;
        Boolean stateResolved = null;
        String priority = null;
        var versionIds = new LinkedHashSet<String>();
        String assigneeAccountId = null;

        JsonNode customFields = child(node, "customFields");
        if (customFields != null && !customFields.isNull()) {
            requireArray(customFields);
            for (JsonNode cf : customFields) {
                requireObject(cf);
                String fieldName = requireText(cf, "name", MAX_LABEL_LENGTH);
                JsonNode value = child(cf, "value");
                if (value == null || value.isNull()) {
                    continue;
                }
                if (schema.fixVersionField() != null
                        && schema.fixVersionField().name().equalsIgnoreCase(fieldName)) {
                    extractVersionIds(value, versionIds);
                } else if (schema.assigneeFieldName() != null
                        && schema.assigneeFieldName().equalsIgnoreCase(fieldName)) {
                    assigneeAccountId = extractSingleAssignee(value);
                } else if ("State".equalsIgnoreCase(fieldName)) {
                    requireObject(value);
                    status = optionalText(value, "name", MAX_LABEL_LENGTH);
                    stateResolved = optionalBoxedBoolean(value, "isResolved");
                } else if ("Type".equalsIgnoreCase(fieldName)) {
                    type = extractNamedOrString(value);
                } else if ("Priority".equalsIgnoreCase(fieldName)) {
                    priority = extractNamedOrString(value);
                }
            }
        }

        String statusCategory = deriveStatusCategory(status, stateResolved, resolvedAt);
        String resolution = "done".equals(statusCategory)
                ? (status != null && !status.isBlank() ? status : "Resolved")
                : null;
        return Optional.of(new IssueObservation(
                new IssueIdentity(expectedProjectId, id),
                issueKey,
                summary,
                type,
                status,
                statusCategory,
                priority,
                resolution,
                createdAt,
                updatedAt,
                resolvedAt,
                List.copyOf(versionIds),
                connection.browse(issueKey),
                observedAt,
                assigneeAccountId));
    }

    static String deriveStatusCategory(String status, Boolean stateResolved, Instant resolvedAt) {
        if (resolvedAt != null || Boolean.TRUE.equals(stateResolved)) {
            return "done";
        }
        if (status != null) {
            String normalized = status.strip().toLowerCase(Locale.ROOT);
            if ("submitted".equals(normalized) || "open".equals(normalized)
                    || "new".equals(normalized) || "to do".equals(normalized)) {
                return "new";
            }
        }
        return "indeterminate";
    }

    static boolean validQueryToken(String value) {
        return value != null && !value.isBlank()
                && value.codePoints().noneMatch(c -> c == '{' || c == '}' || Character.isISOControl(c));
    }

    static TicketException invalidResponse() {
        return new TicketException(Code.INVALID_RESPONSE, YouTrackMessages.format("ticketing.youTrackPayload.youtrack.returned.an.unexpected.response.payload"));
    }

    private static void extractVersionIds(JsonNode value, Set<String> out) {
        if (value.isArray()) {
            for (JsonNode item : value) {
                requireObject(item);
                out.add(requireId(item, "id"));
            }
            return;
        }
        if (value.isObject()) {
            out.add(requireId(value, "id"));
            return;
        }
        throw invalidResponse();
    }

    private static String extractSingleAssignee(JsonNode value) {
        if (value.isObject()) {
            return extractUserId(value);
        }
        if (value.isArray()) {
            // Multi-user assignee fields cannot be uniquely attributed when > 1 user is selected.
            return value.size() == 1 ? extractUserId(requireObject(value.get(0))) : null;
        }
        throw invalidResponse();
    }

    private static String extractUserId(JsonNode user) {
        String ringId = optionalId(user, "ringId");
        if (ringId != null) {
            return ringId;
        }
        String id = optionalId(user, "id");
        if (id != null) {
            return id;
        }
        String login = optionalId(user, "login");
        if (login != null) {
            return login;
        }
        throw invalidResponse();
    }

    private static String extractNamedOrString(JsonNode value) {
        if (value.isObject()) {
            return optionalText(value, "name", MAX_LABEL_LENGTH);
        }
        if (value.isString()) {
            return normalizeText(value.stringValue(), MAX_LABEL_LENGTH);
        }
        throw invalidResponse();
    }

    private static boolean isTypedValue(String valueType, String prefix) {
        return valueType != null && (valueType.equals(prefix) || valueType.startsWith(prefix + "["));
    }

    static JsonNode child(JsonNode object, String field) {
        return object != null && object.isObject() ? object.get(field) : null;
    }

    static JsonNode requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalidResponse();
        }
        return node;
    }

    static JsonNode requireArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw invalidResponse();
        }
        return node;
    }

    static String requireEntityId(JsonNode object, String field) {
        String id = requireId(object, field);
        if (!ENTITY_ID.matcher(id).matches()) {
            throw invalidResponse();
        }
        return id;
    }

    static String requireId(JsonNode object, String field) {
        String value = optionalId(object, field);
        if (value == null) {
            throw invalidResponse();
        }
        return value;
    }

    static String optionalId(JsonNode object, String field) {
        JsonNode node = child(object, field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw invalidResponse();
        }
        String value = node.stringValue().strip();
        if (value.isEmpty() || value.length() > MAX_ID_LENGTH
                || value.contains("/") || value.contains("..")
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidResponse();
        }
        return value;
    }

    static String requireText(JsonNode object, String field, int maxLength) {
        String value = optionalText(object, field, maxLength);
        if (value == null || value.isBlank()) {
            throw invalidResponse();
        }
        return value;
    }

    static String optionalText(JsonNode object, String field, int maxLength) {
        JsonNode node = child(object, field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw invalidResponse();
        }
        return normalizeText(node.stringValue(), maxLength);
    }

    private static String normalizeText(String raw, int maxLength) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    static boolean optionalBoolean(JsonNode object, String field, boolean fallback) {
        Boolean value = optionalBoxedBoolean(object, field);
        return value != null ? value : fallback;
    }

    static Boolean optionalBoxedBoolean(JsonNode object, String field) {
        JsonNode node = child(object, field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw invalidResponse();
        }
        return node.booleanValue();
    }

    private static Instant requireTimestamp(JsonNode object, String field) {
        Instant value = optionalTimestamp(object, field);
        if (value == null) {
            throw invalidResponse();
        }
        return value;
    }

    private static Instant optionalTimestamp(JsonNode object, String field) {
        JsonNode node = child(object, field);
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            if (node.isIntegralNumber() && node.canConvertToLong()) {
                return Instant.ofEpochMilli(node.longValue());
            }
            if (node.isString()) {
                return Instant.parse(node.stringValue().strip());
            }
        } catch (DateTimeException | ArithmeticException exception) {
            throw invalidResponse();
        }
        throw invalidResponse();
    }

    private static LocalDate parseReleaseDate(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            if (node.isIntegralNumber() && node.canConvertToLong()) {
                return Instant.ofEpochMilli(node.longValue()).atZone(ZoneOffset.UTC).toLocalDate();
            }
            if (node.isString()) {
                return LocalDate.parse(node.stringValue().strip());
            }
        } catch (DateTimeException | ArithmeticException exception) {
            throw invalidResponse();
        }
        throw invalidResponse();
    }
}
