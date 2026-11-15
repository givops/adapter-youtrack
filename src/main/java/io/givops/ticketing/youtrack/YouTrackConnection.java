package io.givops.ticketing.youtrack;

import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import java.net.URI;
import java.util.List;
import io.givops.adapters.ContextSigner;

final class YouTrackConnection {
    private static final int MAX_TOKEN_LENGTH = 4096;
    private final URI site;
    private final String api;
    private final YouTrackProperties.Instance credentials;
    private final ContextSigner signer;

    YouTrackConnection(ContextSigner signer, InstanceSource source, YouTrackProperties.Instance credentials) {
        if (!TicketProvider.YOUTRACK.equals(source.provider()) || source.instanceId() == null
                || source.instanceKey() == null) {
            throw invalidConfiguration();
        }
        this.credentials = credentials;
        if (credentials == null || missing(credentials.apiToken())) {
            throw new TicketException(Code.MISSING_CREDENTIALS, YouTrackMessages.format("ticketing.youTrackConnection.youtrack.credentials.are.missing"));
        }
        this.signer = java.util.Objects.requireNonNull(signer);
        if (!validToken(credentials.apiToken())) {
            throw invalidConfiguration();
        }
        try {
            this.site = normalize(source.baseUrl());
            if (!site.equals(normalize(URI.create(credentials.expectedBaseUrl())))) {
                throw invalidConfiguration();
            }
            this.api = site.toString() + "/api";
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidConfiguration();
        }
    }

    static boolean validToken(String token) {
        return !missing(token) && token.length() <= MAX_TOKEN_LENGTH
                && token.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    static URI normalize(URI uri) {
        return YouTrackBaseUrls.normalizeYouTrackBaseUrl(uri, true, YouTrackConnection::invalidConfiguration);
    }

    URI endpoint(String path) {
        return URI.create(api + path);
    }

    URI browse(String issueKey) {
        return URI.create(site.toString() + "/issue/" + issueKey);
    }

    URI projectUrl(String externalProjectId) {
        return URI.create(site.toString() + "/projects/" + externalProjectId);
    }

    String authorization() {
        return "Bearer " + credentials.apiToken();
    }

    String context(InstanceSource source) {
        return signer.sign(TicketProvider.YOUTRACK.name(), "givops-youtrack-v1", List.of(
                source.instanceId().toString(), source.instanceKey(), site.toString(), api,
                credentials.apiToken(), credentials.accessRevision() == null ? "" : credentials.accessRevision()));
    }

    static TicketException invalidConfiguration() {
        return new TicketException(Code.INVALID_CONFIGURATION, YouTrackMessages.format("ticketing.youTrackConnection.the.youtrack.connection.configuration.is.invalid"));
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }
}
