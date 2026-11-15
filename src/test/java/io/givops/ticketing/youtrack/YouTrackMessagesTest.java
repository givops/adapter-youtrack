package io.givops.ticketing.youtrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YouTrackMessagesTest {
    @Test
    void providerCatalogPreservesQuotedExternalTextWithoutTreatingItAsAMessage() {
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            assertThat(YouTrackMessages.format("ticketing.youTrackWriteAdapter.version.not.unique",
                    Map.of("versionName", "release's {candidate}<script>")))
                    .isEqualTo("The Fix version 'release's {candidate}<script>' does not exist uniquely in the project bundle.");
            assertThat(YouTrackMessages.format("ticketing.youTrackWriteAdapter.invalid.single.line",
                    Map.of("label", "issue summary", "maximum", "512")))
                    .isEqualTo("The issue summary must contain between 1 and 512 non-control characters.");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void absentParametersAndKeysFailInsteadOfDisplayingPlaceholders() {
        assertThatThrownBy(() -> YouTrackMessages.format("ticketing.youTrackWriteAdapter.version.not.unique"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YouTrackMessages.format("unknown.key"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
