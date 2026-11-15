package io.givops.ticketing.youtrack;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialAccessException;
import io.givops.adapters.testkit.AdapterTestkit;
import java.util.Map;

/** Synthetic host for provider HTTP fixtures; no database or live credentials are used. */
final class YouTrackTestCredentials {
    static final ContextSigner SIGNER = AdapterTestkit.signer("synthetic-context-secret-for-tests");

    static YouTrackCredentials credentials(YouTrackProperties properties) {
        return new YouTrackCredentials(request -> {
            var configured = properties.instances().get(request.instanceKey());
            if (configured == null) throw new CredentialAccessException();
            return AdapterTestkit.lease(Map.of("apiToken", configured.apiToken()),
                    Map.of("baseUrl", configured.expectedBaseUrl(), "instanceKey", request.instanceKey()),
                    configured.accessRevision());
        }, SIGNER);
    }
}
