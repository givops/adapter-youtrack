# Contributing

Use an isolated branch and worktree. Keep source, tests, docs and messages in
English, and retain remote identifiers and user text exactly as received.

Run `./scripts/build.sh` with Java 25 and Maven 3.9+. Tests use the public SDK
fixture signer and credential leases, mocked HTTP replies and controlled clocks;
no live YouTrack account, database or private repository is required.
`mvn clean verify` suffices once the exact public SDK dependencies are installed.
Run `git diff --check` before submitting changes.

Preserve HTTPS destination pins, redirect refusal, bounded response/request
budgets, duplicate-JSON checks, error classifications, cancellation and credential
freshness. Write requests must not be automatically retried after an ambiguous
response. Keep project/version/issue read and create behavior covered by synthetic
HTTP tests. Host database integration tests belong in the host repository.

Use SDK types at public boundaries. Never import private core credential or
catalog classes. Keep provider validation, runtime configuration, capability
metadata and namespaced YAML resources in this jar. New messages use named MF1
parameters. Test literal braces, apostrophes and untrusted parameter values.
Declare only implemented capabilities; do not invent server compatibility ranges.

## Releases

1. Choose the adapter artifact version in `pom.xml` and
   `YouTrackProviderDefinition.adapterVersion()` and
   `src/main/resources/META-INF/givops-adapter.properties`. Keep SDK API compatibility
   explicit and update the README coordinates when changing the artifact version.
2. Pin `sdk-revision.txt` to a published public SDK commit and verify the default
   bootstrap command from a checkout that has no private repository access.
3. Review the change and tag the reviewed commit `v<artifact-version>`.
4. The release workflow checks that version, builds packages with tests skipped, and
   creates a draft GitHub release with the binary jar, source jar, POM, license,
   SDK revision and SHA-256 checksums. Review its artifacts before publication.

Run `./scripts/verify-local.sh` on the final commit before merging; Java tests run locally. GitHub Actions is reserved for package releases. Downloadable
GitHub release artifacts do not require a Maven publishing account. Consumers
may build from public source or install the downloaded POM/jar in their own Maven
repository. Do not commit generated jars, secrets or runtime fixture state.

Initial provider behavior and tests were extracted from GivOps backend revision
`fba6d70e987eec92aae1be3678bd40d364a81b16`. The public library retains its existing
`io.givops.ticketing.youtrack` package to keep the extraction small; its runtime
dependencies are exclusively public artifacts.
