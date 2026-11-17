package io.givops.ticketing.youtrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialAccessException;
import io.givops.adapters.CredentialHost;
import io.givops.adapters.testkit.AdapterTestkit;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketProviderAdapter;
import io.givops.ticketing.port.TicketProviderDefinition;
import io.givops.ticketing.port.TicketWriteAdapter;
import io.givops.ticketing.port.TicketBrowseAdapter;
import io.givops.ticketing.port.TicketContentAdapter;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class YouTrackConfigurationTest {
    private ApplicationContextRunner context(CredentialHost host) {
        return new ApplicationContextRunner()
                .withBean(CredentialHost.class, () -> host)
                .withBean(ContextSigner.class, () -> YouTrackTestCredentials.SIGNER)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withBean(TicketProperties.class, () -> new TicketProperties(true, java.time.Duration.ofMinutes(2), java.time.Duration.ofSeconds(10),
                        100, 20, 2097152, 16777216, 500, 500, 1000, 5000, 8388608, 20, 500, 10000,
                        java.time.Duration.ofMinutes(15), 50, 500))
                .withConfiguration(AutoConfigurations.of(YouTrackConfiguration.class));
    }

    @Test
    void discoversProviderAndReadWriteCapabilitiesWithoutComponentScanningOrCredentials() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
                .contains(YouTrackConfiguration.class.getName());
        var captures = new AtomicInteger();
        context(request -> { captures.incrementAndGet(); throw new CredentialAccessException(); }).run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(TicketProviderDefinition.class)
                    .hasSingleBean(TicketProviderAdapter.class).hasSingleBean(TicketWriteAdapter.class)
                    .hasSingleBean(TicketBrowseAdapter.class).hasSingleBean(TicketContentAdapter.class);
            var definition = application.getBean(TicketProviderDefinition.class);
            AdapterTestkit.verifyDefinition(definition);
            assertThat(definition.provider()).isEqualTo(TicketProvider.valueOf("YOUTRACK"));
            assertThat(definition.capabilities()).containsExactly("ticketing.project-create", "ticketing.version-create", "ticketing.issue-create", "ticketing.read", "ticketing.navigation", "ticketing.version-membership", "ticketing.browse", "ticketing.content");
            assertThat(definition.apiProfiles()).containsExactly("youtrack-rest-api");
            assertThat(definition.credentialFields()).singleElement().satisfies(field -> {
                assertThat(field.name()).isEqualTo("apiToken");
                assertThat(field.label()).isEqualTo("API token");
            });
            assertThat(captures).hasValue(0);
        });
    }

    @Test
    void artifactIdentityMatchesTheRegisteredDescriptor() throws Exception {
        var properties = new java.util.Properties();
        try (var stream = getClass().getResourceAsStream("/META-INF/givops-adapter.properties")) {
            assertThat(stream).isNotNull();
            properties.load(stream);
        }
        var definition = new YouTrackProviderDefinition(new YouTrackProperties(Map.of()));
        assertThat(properties.getProperty("adapterId")).isEqualTo(definition.adapterId());
        assertThat(properties.getProperty("provider")).isEqualTo(definition.provider().name());
        assertThat(properties.getProperty("adapterVersion")).isEqualTo(definition.adapterVersion());
        assertThat(properties.getProperty("sdkApiVersion")).isEqualTo(definition.sdkApiVersion());
    }

    @Test
    void bindsPublicRuntimeSettingsAndKeepsFreshnessChecksOnReadAndWritePorts() {
        var source = new InstanceSource(UUID.randomUUID(), "test", TicketProvider.valueOf("YOUTRACK"),
                URI.create("https://example.test/youtrack"), 1);
        var lease = AdapterTestkit.lease(Map.of("apiToken", "synthetic-token"),
                Map.of("instanceKey", "test", "baseUrl", source.baseUrl().toString()), "runtime-id:1");
        context(request -> lease)
                .withPropertyValues("givops.ticketing.youtrack.instances.test.expected-base-url=https://example.test/youtrack",
                        "givops.ticketing.youtrack.instances.test.api-token=synthetic-token",
                        "givops.ticketing.youtrack.instances.test.access-revision=1")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBean(YouTrackProperties.class).instances()).containsKey("test");
                    var reads = application.getBean(TicketProviderAdapter.class);
                    var writes = application.getBean(TicketWriteAdapter.class);
                    var captured = reads.captureAccess(source);
                    assertThat(reads.current(source, captured, false)).isTrue();
                    writes.requireCurrent(source, captured, true);
                    assertThat(lease.lastLocked()).isTrue();
                    lease.invalidate();
                    assertSuperseded(() -> reads.current(source, captured, false));
                    assertSuperseded(() -> writes.requireCurrent(source, captured, true));
                    assertThat(reads.current(source, () -> "foreign", false)).isFalse();
                    assertSuperseded(() -> writes.requireCurrent(source, () -> "foreign", true));
                });
    }

    private static void assertSuperseded(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TicketException.class,
                exception -> assertThat(exception.code()).isEqualTo(TicketException.Code.SUPERSEDED));
    }
}
