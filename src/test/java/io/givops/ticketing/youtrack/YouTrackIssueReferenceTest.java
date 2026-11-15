package io.givops.ticketing.youtrack;

import static org.assertj.core.api.Assertions.assertThat;

import io.givops.ticketing.port.TicketProviderDefinition.IssueReference;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YouTrackIssueReferenceTest {
    private final YouTrackProviderDefinition definition = new YouTrackProviderDefinition(new YouTrackProperties(Map.of()));

    @Test
    void preservesRootContextAndOptionalSlugRecognition() {
        assertThat(definition.issueReference(URI.create("https://team.youtrack.cloud/issue/abc_1-123")))
                .contains(new IssueReference("ABC_1-123", ""));
        assertThat(definition.issueReference(URI.create("https://example.test/youtrack/issue/OPS-9/A-title%20with-text?view=full#details")))
                .contains(new IssueReference("OPS-9", "/youtrack"));
        assertThat(definition.issueReference(URI.create("https://example.test/youtrack/issue/OPS-9/")))
                .contains(new IssueReference("OPS-9", "/youtrack"));
    }

    @Test
    void rejectsForeignPathsNestedContextsAndEncodedOrInvalidKeys() {
        for (String path : new String[] {"/browse/OPS-9", "/youtrack/api/issues/OPS-9", "/a/b/issue/OPS-9",
                "/issue/%4FPS-9", "/issue/OPS-0", "/issue/OPS-01", "/issue/X-1", "/issue/OPS-1/trailing/path",
                "/issue/OPS-12345678901234567890", "/issue/" + "X".repeat(65) + "-1"}) {
            assertThat(definition.issueReference(URI.create("https://example.test" + path))).as(path).isEmpty();
        }
        assertThat(definition.issueReference(null)).isEmpty();
        assertThat(definition.issueReference(URI.create("urn:synthetic:OPS-9"))).isEmpty();
    }

    @Test
    void leavesConfiguredOriginAndContextRoutingToTheHost() {
        // This hook recognizes only provider syntax. The host must still compare
        // the captured origin/context with each configured instance before routing.
        assertThat(definition.issueReference(URI.create("https://other.example/another-context/issue/OPS-9")))
                .contains(new IssueReference("OPS-9", "/another-context"));
    }
}
