package io.givops.ticketing.youtrack;

import io.givops.adapters.CredentialField;
import io.givops.adapters.CredentialRequest;
import io.givops.adapters.RuntimeCredential;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketProviderDefinition;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Provider metadata and validation are available even when credentials have not been configured. */
public final class YouTrackProviderDefinition implements TicketProviderDefinition {
    private static final Pattern ISSUE_PATH = Pattern.compile(
            "(?:(/[A-Za-z0-9._-]+))?/issue/(" + IssueReference.KEY_PATTERN + ")(?:/[A-Za-z0-9._~%-]*)?");
    private final YouTrackProperties runtime;

    public YouTrackProviderDefinition(YouTrackProperties runtime) { this.runtime = Objects.requireNonNull(runtime); }

    @Override public TicketProvider provider() { return TicketProvider.YOUTRACK; }
    @Override public String displayName() { return "YouTrack"; }
    @Override public String adapterId() { return "io.givops:adapter-youtrack"; }
    @Override public String adapterVersion() { return "0.5.0-SNAPSHOT"; }
    @Override public String sdkApiVersion() { return "0.5"; }
    @Override public Map<String, String> commandLabels() { return Map.of("projectOwner", "Project lead login"); }
    @Override public List<String> capabilities() {
        return List.of("ticketing.project-create", "ticketing.version-create", "ticketing.issue-create", "ticketing.read", "ticketing.navigation", "ticketing.version-membership", "ticketing.browse", "ticketing.content");
    }
    @Override public List<String> apiProfiles() { return List.of("youtrack-rest-api"); }
    @Override public List<CredentialField> credentialFields() {
        return List.of(new CredentialField("apiToken", YouTrackMessages.format("youtrack.credentials.api-token"),
                CredentialField.Type.SECRET, true, 4096));
    }
    @Override public String projectKeyPattern() { return "[A-Z][A-Z0-9_]{1,63}"; }
    @Override public String externalIdPattern() { return "[0-9]{1,18}(?:-[0-9]{1,18})?"; }

    @Override public Optional<IssueReference> issueReference(URI candidate) {
        if (candidate == null || candidate.getRawPath() == null) return Optional.empty();
        var match = ISSUE_PATH.matcher(candidate.getRawPath());
        if (!match.matches()) return Optional.empty();
        return Optional.of(new IssueReference(match.group(2).toUpperCase(java.util.Locale.ROOT), match.group(1) == null ? "" : match.group(1)));
    }

    @Override public URI normalizeBaseUrl(URI value) {
        return YouTrackBaseUrls.normalizeYouTrackBaseUrl(value, false, () -> new TicketException(
                Code.INVALID_CONFIGURATION, YouTrackMessages.format(
                        "ticketing.ticketCatalog.the.youtrack.base.url.must.be.an.https.origin.with.an.optional")));
    }

    @Override public void validateProjectKey(String projectKey) {
        if (projectKey == null || !projectKey.matches(projectKeyPattern())) throw YouTrackConnection.invalidConfiguration();
    }

    @Override public void validateProject(String externalId, String projectKey) {
        validateProjectKey(projectKey);
        if (externalId == null || !externalId.matches(externalIdPattern())) throw YouTrackConnection.invalidConfiguration();
    }

    @Override public Map<String, String> validateCredentials(CredentialRequest target, Map<String, String> fields) {
        if (target == null || !provider().name().equals(target.provider()) || target.instanceId() == null
                || target.instanceKey() == null || fields == null || fields.size() != 1
                || !YouTrackConnection.validToken(fields.get("apiToken"))) throw YouTrackConnection.invalidConfiguration();
        normalizeBaseUrl(target.baseUrl());
        return Map.of("apiToken", fields.get("apiToken"));
    }

    @Override public Optional<RuntimeCredential> runtimeCredentials(CredentialRequest target) {
        if (target == null || !provider().name().equals(target.provider()) || target.instanceKey() == null) {
            return Optional.empty();
        }
        var configured = runtime.instances().get(target.instanceKey());
        if (configured == null) return Optional.empty();
        Map<String, String> destinations;
        try {
            destinations = Map.of("baseUrl", YouTrackConnection.normalize(URI.create(configured.expectedBaseUrl())).toString(),
                    "instanceKey", target.instanceKey());
        } catch (TicketException | IllegalArgumentException | NullPointerException exception) {
            // An invalid runtime pin remains identifiable, but cannot match any registered destination.
            destinations = Map.of("baseUrl", "");
        }
        return Optional.of(new RuntimeCredential(Map.of("apiToken", Objects.toString(configured.apiToken(), "")),
                destinations, configured.accessRevision()));
    }
}
