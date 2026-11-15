package io.givops.ticketing.youtrack;

import io.givops.adapters.i18n.MessageCatalog;
import java.util.Map;

/** Messages travel with this adapter jar; no application catalog or Spring context is required. */
final class YouTrackMessages {
    private static final MessageCatalog ENGLISH = MessageCatalog.load(YouTrackMessages.class,
            "/io/givops/adapters/youtrack/i18n/en.yml");

    private YouTrackMessages() { }

    static String format(String key) { return format(key, Map.of()); }

    static String format(String key, Map<String, ?> parameters) { return ENGLISH.format(key, parameters); }
}
