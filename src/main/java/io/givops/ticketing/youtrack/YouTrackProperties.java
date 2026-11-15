package io.givops.ticketing.youtrack;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("givops.ticketing.youtrack")
public record YouTrackProperties(Map<String, Instance> instances) {
    public YouTrackProperties {
        instances = instances == null ? Map.of() : Map.copyOf(instances);
    }

    @Override
    public String toString() {
        return "YouTrackProperties[redacted]";
    }

    public record Instance(String expectedBaseUrl, String apiToken, String accessRevision) {
        @Override
        public String toString() {
            return "Instance[redacted]";
        }
    }
}
