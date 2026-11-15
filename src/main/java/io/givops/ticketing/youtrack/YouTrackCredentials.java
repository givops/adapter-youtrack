package io.givops.ticketing.youtrack;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialAccessException;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.adapters.CredentialHost;
import io.givops.adapters.CredentialLease;
import io.givops.adapters.CredentialRequest;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.port.TicketProviderAdapter;
import java.util.Objects;

/** Captures host-owned credentials without retaining host persistence or encryption types. */
public final class YouTrackCredentials {
    private final CredentialHost host;
    private final ContextSigner signer;

    public YouTrackCredentials(CredentialHost host, ContextSigner signer) {
        this.host = Objects.requireNonNull(host);
        this.signer = Objects.requireNonNull(signer);
    }

    record Captured(YouTrackConnection connection, CredentialLease lease, String context)
            implements TicketProviderAdapter.Access {
        @Override public String toString() { return "Captured[redacted]"; }
    }

    YouTrackConnection connection(InstanceSource source) { return capture(source).connection(); }

    Captured capture(InstanceSource source) {
        CredentialLease lease;
        try {
            lease = host.capture(new CredentialRequest(source.instanceId(), source.instanceKey(),
                source.provider().name(), source.baseUrl()));
        } catch (CredentialAccessException exception) {
            throw new TicketException(Code.MISSING_CREDENTIALS, YouTrackMessages.format(
                    "ticketing.youTrackCredentials.youtrack.credentials.are.unavailable"));
        }
        if (!Objects.equals(source.instanceKey(), lease.destinations().get("instanceKey"))) {
            throw YouTrackConnection.invalidConfiguration();
        }
        var value = new YouTrackProperties.Instance(lease.destinations().get("baseUrl"),
                lease.fields().get("apiToken"), lease.accessRevision());
        var connection = new YouTrackConnection(signer, source, value);
        return new Captured(connection, lease, connection.context(source));
    }

    void requireCurrent(Captured captured, boolean locked) {
        try {
            captured.lease().requireCurrent(locked);
        } catch (CredentialAccessException exception) {
            throw new TicketException(Code.SUPERSEDED, YouTrackMessages.format(
                    "ticketing.youTrackCredentials.the.youtrack.credential.context.changed"));
        }
    }
}
