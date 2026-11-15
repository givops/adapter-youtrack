package io.givops.ticketing.youtrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialAccessException;
import io.givops.adapters.CredentialHost;
import io.givops.adapters.CredentialLease;
import io.givops.adapters.CredentialRequest;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class YouTrackCredentialsTest {
    private static final String TOKEN = "synthetic-youtrack-token";
    private static final UUID ID = UUID.fromString("2972b7b5-03cf-4058-a4a2-17a2a6c3897b");
    private static final URI SITE = URI.create("https://git.example.test/youtrack");
    private static final InstanceSource SOURCE = new InstanceSource(ID, "yt-hosted", TicketProvider.YOUTRACK, SITE, 3);
    private static final CredentialRequest REQUEST = new CredentialRequest(ID, "yt-hosted", "YOUTRACK", SITE);

    @Test
    void validatesTokensAndDestinationWithoutNeedingAHostSecret() {
        var definition = definition(Map.of());
        assertThat(definition.validateCredentials(REQUEST, Map.of("apiToken", TOKEN))).containsEntry("apiToken", TOKEN);
        for (String token : List.of("", "   ", "bad\ntoken", "bad token", "x".repeat(4097))) {
            assertFailure(Code.INVALID_CONFIGURATION, () -> definition.validateCredentials(REQUEST, Map.of("apiToken", token)));
        }
        assertFailure(Code.INVALID_CONFIGURATION, () -> definition.validateCredentials(
                new CredentialRequest(ID, "yt-hosted", "YOUTRACK", URI.create("https://git.example.test/api")),
                Map.of("apiToken", TOKEN)));
        assertFailure(Code.INVALID_CONFIGURATION, () -> definition.validateCredentials(REQUEST, Map.of("apiToken", TOKEN, "unexpected", "value")));
    }

    @Test
    void runtimeCredentialsRetainDestinationPinsAndInvalidSelections() {
        var valid = new YouTrackProperties.Instance("https://git.example.test/youtrack/", TOKEN, "rev-1");
        var invalid = new YouTrackProperties.Instance("http://insecure.example.test/youtrack", TOKEN, "rev-2");
        var definition = definition(Map.of("yt-hosted", valid, "yt-invalid", invalid));
        assertThat(valid.toString()).doesNotContain(TOKEN);
        assertThat(definition.runtimeCredentials(REQUEST)).hasValueSatisfying(value -> {
            assertThat(value.fields()).containsExactlyEntriesOf(Map.of("apiToken", TOKEN));
            assertThat(value.expectedDestinations()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("baseUrl", SITE.toString(), "instanceKey", "yt-hosted"));
            assertThat(value.accessRevision()).isEqualTo("rev-1");
            assertThat(value.toString()).doesNotContain(TOKEN);
        });
        assertThat(definition.runtimeCredentials(new CredentialRequest(ID, "yt-invalid", "YOUTRACK", SITE)))
                .hasValueSatisfying(value -> assertThat(value.expectedDestinations()).containsExactlyEntriesOf(Map.of("baseUrl", "")));
        assertThat(definition.runtimeCredentials(new CredentialRequest(ID, "unknown", "YOUTRACK", SITE))).isEmpty();
    }

    @Test
    void capturesBearerAccessAndSignsExactlyTheExistingContextParts() {
        var host = mock(CredentialHost.class);
        var signer = mock(ContextSigner.class);
        var lease = lease("access-id:runtime-revision");
        when(host.capture(REQUEST)).thenReturn(lease);
        when(signer.sign(any(), any(), any())).thenReturn("opaque-signed-context");
        var credentials = new YouTrackCredentials(host, signer);
        var captured = credentials.capture(SOURCE);
        assertThat(captured.connection().authorization()).isEqualTo("Bearer " + TOKEN);
        assertThat(captured.context()).isEqualTo("opaque-signed-context");
        assertThat(captured.toString()).doesNotContain(TOKEN, "opaque-signed-context");
        verify(signer).sign("YOUTRACK", "givops-youtrack-v1", List.of(ID.toString(), "yt-hosted", SITE.toString(),
                SITE + "/api", TOKEN, "access-id:runtime-revision"));
        credentials.requireCurrent(captured, false);
        credentials.requireCurrent(captured, true);
        verify(lease).requireCurrent(false);
        verify(lease).requireCurrent(true);
    }

    @Test
    void translatesOnlyHostAccessFailuresAndRejectsMismatchedPins() {
        var host = mock(CredentialHost.class);
        var credentials = new YouTrackCredentials(host, YouTrackTestCredentials.SIGNER);
        when(host.capture(REQUEST)).thenThrow(new CredentialAccessException());
        assertFailure(Code.MISSING_CREDENTIALS, () -> credentials.capture(SOURCE));
        var lease = lease("1");
        doReturn(lease).when(host).capture(REQUEST);
        var captured = credentials.capture(SOURCE);
        doThrow(new CredentialAccessException()).when(lease).requireCurrent(true);
        assertFailure(Code.SUPERSEDED, () -> credentials.requireCurrent(captured, true));
        doThrow(new CredentialAccessException()).when(lease).requireCurrent(false);
        assertFailure(Code.SUPERSEDED, () -> credentials.requireCurrent(captured, false));
        when(lease.destinations()).thenReturn(Map.of("baseUrl", SITE.toString(), "instanceKey", "other"));
        assertFailure(Code.INVALID_CONFIGURATION, () -> credentials.capture(SOURCE));
        when(lease.destinations()).thenReturn(Map.of("baseUrl", "https://other.example.test/youtrack", "instanceKey", "yt-hosted"));
        assertFailure(Code.INVALID_CONFIGURATION, () -> credentials.capture(SOURCE));
        when(host.capture(REQUEST)).thenThrow(new IllegalStateException("programming error"));
        assertThatThrownBy(() -> credentials.capture(SOURCE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runtimeRevisionChangesAndTokensChangeOpaqueContextWithoutLeakingSecrets() {
        var first = YouTrackTestCredentials.credentials(new YouTrackProperties(Map.of("yt-hosted",
                new YouTrackProperties.Instance(SITE.toString(), TOKEN, "1")))).capture(SOURCE);
        var changed = YouTrackTestCredentials.credentials(new YouTrackProperties(Map.of("yt-hosted",
                new YouTrackProperties.Instance(SITE.toString(), TOKEN, "2")))).capture(SOURCE);
        var rotated = YouTrackTestCredentials.credentials(new YouTrackProperties(Map.of("yt-hosted",
                new YouTrackProperties.Instance(SITE.toString(), "rotated-synthetic-token", "1")))).capture(SOURCE);
        assertThat(first.context()).isNotEqualTo(changed.context()).isNotEqualTo(rotated.context()).doesNotContain(TOKEN);
    }

    private static CredentialLease lease(String revision) {
        var lease = mock(CredentialLease.class);
        when(lease.fields()).thenReturn(Map.of("apiToken", TOKEN));
        when(lease.destinations()).thenReturn(Map.of("baseUrl", SITE.toString(), "instanceKey", "yt-hosted"));
        when(lease.accessRevision()).thenReturn(revision);
        return lease;
    }

    private static YouTrackProviderDefinition definition(Map<String, YouTrackProperties.Instance> instances) {
        return new YouTrackProviderDefinition(new YouTrackProperties(instances));
    }

    private static void assertFailure(Code code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(TicketException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getMessage()).doesNotContain(TOKEN);
        });
    }
}
