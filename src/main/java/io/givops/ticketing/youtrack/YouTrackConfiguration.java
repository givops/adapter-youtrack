package io.givops.ticketing.youtrack;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.ConnectionClients;
import org.springframework.beans.factory.ObjectProvider;
import io.givops.adapters.CredentialHost;
import io.givops.ticketing.config.TicketProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/** Registers the installed provider without relying on host component scanning. */
@AutoConfiguration
@EnableConfigurationProperties(YouTrackProperties.class)
public class YouTrackConfiguration {
    @Bean
    YouTrackProviderDefinition youTrackProviderDefinition(YouTrackProperties runtime) {
        return new YouTrackProviderDefinition(runtime);
    }

    @Bean
    YouTrackCredentials youTrackCredentials(CredentialHost host, ContextSigner signer) {
        return new YouTrackCredentials(host, signer);
    }

    @Bean
    YouTrackTransport youTrackTransport(TicketProperties limits, Clock clock, ObjectProvider<ConnectionClients> clients) {
        var client = YouTrackTransport.clientBuilder(limits.requestTimeout()).build();
        return new YouTrackTransport(client, limits.maxResponseBytes(), clock, clients.getIfAvailable(() -> ConnectionClients.SYSTEM));
    }

    @Bean
    YouTrackWriteTransport youTrackWriteTransport(TicketProperties limits, Clock clock, ObjectProvider<ConnectionClients> clients) {
        var client = YouTrackTransport.clientBuilder(limits.requestTimeout()).build();
        return new YouTrackWriteTransport(client, limits.maxResponseBytes(), clock, clients.getIfAvailable(() -> ConnectionClients.SYSTEM));
    }

    @Bean
    YouTrackAdapter youTrackAdapter(YouTrackCredentials credentials, TicketProperties limits,
                                   YouTrackTransport transport, JsonMapper mapper, Clock clock) {
        return new YouTrackAdapter(credentials, limits, transport, mapper, clock);
    }

    @Bean
    YouTrackBrowserAdapter youTrackBrowserAdapter(YouTrackCredentials credentials, TicketProperties limits,
                                                YouTrackTransport transport, JsonMapper mapper) {
        return new YouTrackBrowserAdapter(credentials, limits, transport, mapper);
    }

    @Bean
    YouTrackWriteAdapter youTrackWriteAdapter(YouTrackCredentials credentials, TicketProperties limits,
                                             YouTrackTransport reads, YouTrackWriteTransport writes,
                                             JsonMapper mapper, Clock clock) {
        return new YouTrackWriteAdapter(credentials, limits, reads, writes, mapper, clock);
    }
}
