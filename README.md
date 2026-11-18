# GivOps YouTrack adapter

A standalone Java library providing YouTrack ticketing reads, version membership,
and project, version and issue creation through the public [GivOps adapter
SDK](https://github.com/givops/adapter-sdk). No private GivOps source is needed to
build or test it. Licensed under [Apache-2.0](LICENSE).

## Compatibility and scope

- Java 25, Spring Boot 4.0.9 and Jackson 3.
- Maven artifact: `io.givops:adapter-youtrack:0.5.0-SNAPSHOT`.
- SDK API: `0.5`. Artifact version, SDK compatibility, YouTrack product version,
  and API profile are separate identifiers.
- Provider: `YOUTRACK`; API profile: `youtrack-rest-api`, using the current `/api`
  REST dialect at an HTTPS origin or one context path, such as
  `https://youtrack.example.test/youtrack`.
- Capabilities: `ticketing.read`, `ticketing.version-membership`,
  `ticketing.project-create`, `ticketing.version-create`, `ticketing.issue-create`,
  `ticketing.navigation`, `ticketing.browse`, and `ticketing.content`.
  The library does not implement report or release-command adapters.

The adapter does not claim a tested YouTrack server-version range. An unknown
product version does not disable reads. Optional product-version inspection is
not implemented; compatibility inspection therefore remains unknown. Unit and
HTTP fixture tests make no live YouTrack requests.

The provider definition recognizes `/issue/KEY-123` URLs with an optional single
context path and title slug. This adapter retains YouTrack's project-number key syntax;
the host still validates the configured origin/context and resolves ambiguous
project references before routing an issue.

## Build

Install a Java 25 JDK, Maven 3.9+, Git and Bash, then run:

```sh
./scripts/build.sh
```

The script clones the public SDK at the exact commit in `sdk-revision.txt`, builds
and installs its public modules into your Maven cache, then runs this library's
clean verification. It needs ordinary public Maven Central and GitHub access,
without GitHub Packages authentication. Outputs are in `target/`.

For coordinated local SDK development:

```sh
GIVOPS_SDK_SOURCE=/absolute/path/to/adapter-sdk ./scripts/build.sh
```

This rebuilds the specified SDK checkout. Do not share its output directory with
a concurrent SDK build. Once the pinned SDK has been installed, `mvn clean verify`
builds only this repository. `mvn install` additionally installs this adapter in
your local Maven cache for host development.

## Install or replace the adapter

Download the adapter jar and checksum from this repository's GitHub Releases, or
use the verified jar from `target/`. For the GivOps container, mount an adapter directory read-only at
`/opt/givops/adapters` and restart the same core image. The mount replaces the
bundled adapter directory as a whole, so include every provider jar you want
installed in that directory. Replace the old jar rather than loading
two versions of the same provider. The host owns the SDK jars; the adapter jar is
not shaded and does not bundle a second SDK or a private application.

Boot discovers `YouTrackConfiguration` through `AutoConfiguration.imports`; the
host does not need to scan the provider package. Registration exposes the provider
and credential form even before credentials exist. A compatible host can add or
replace the jar without rebuilding core or frontend code. See the host's deployment
instructions for its adapter directory and restart procedure.

The host supplies `CredentialHost`, `ContextSigner`, `Clock`, `JsonMapper` and
`TicketProperties`. It owns credential storage, authorization, transaction locks,
request budgets and signing secrets. The adapter uses opaque leases and asks the
host to revalidate freshness, including locked checks before publication.

## Credentials and runtime configuration

The provider definition declares one secret field, `apiToken` (maximum 4096
characters, no whitespace or control characters). Managed credentials use the
host's generic adapter credential form. Tokens never belong in a base URL.

Optional runtime credentials bind through `givops.ticketing.youtrack.instances`:

```yaml
givops:
  ticketing:
    youtrack:
      instances:
        team:
          expected-base-url: https://youtrack.example.test/youtrack
          api-token: ${YOUTRACK_API_TOKEN}
          access-revision: "1"
```

The instance key and normalized base URL must match the registered destination.
Change `access-revision` when changing runtime access. The host's signing-secret
configuration remains host-owned; the adapter accepts no raw context secret.
Configuration and credential captures redact their string representation.

## Messages and development

English YAML/MF1 messages ship under
`io/givops/adapters/youtrack/i18n/en.yml` inside this jar. The public SDK validates
and formats that explicit resource. No core catalog index or frontend bundle is
required. Provider names, issue summaries and other external text stay data;
they are never compiled as message templates.

See [CONTRIBUTING.md](CONTRIBUTING.md) for tests, provider changes and releases.

### SDK API 0.5 compatibility

This source requires the exact public SDK API 0.5 and artifact version
0.5.0-SNAPSHOT. Observation acquisition is metadata-only: issue descriptions and
comments are not mapped. Existing creation/write descriptions retain their existing behavior. The `ticketing.navigation` capability supplies provider-owned project
space links without a content fetch. A global ticket ID does not imply a local
issue number, so an unknown ticket link remains absent.

The API 0.5 source update is pending the merged SDK source pin.
`sdk-revision.txt` still selects the prior API 0.4 commit
`762b642cedc1b82bb4915af56313e6e3c194a04f`; use `GIVOPS_SDK_SOURCE` with
the reviewed API 0.5 source for coordinated verification until the pin is updated.
The artifact remains a development snapshot; a source pin does not imply a tagged release.

## Transient ticket browser

The browser and content ports are registered independently of observation reads.
The browser supports the ordinary `ACTIVE` issue collection, including resolved
issues. Resolution is an outcome, not archival. Drafts are excluded; archived and
draft browsing are not advertised. `key-asc` sorts by the numeric issue-key suffix.

Search is a case-insensitive NFC-normalized literal substring of the display key
or title (`text:key-title-literal-nfc`). Status names, categories (the native
`Type` field), and tag labels use exact native strings. Filter families combine
with AND; multiple values within a family, including labels, combine with OR.
Names containing YouTrack search operators stay literal data. Only these filters
and this sort are advertised.

Each list call scans the bound project's metadata through the documented
[`/api/issues` endpoint](https://www.jetbrains.com/help/youtrack/devportal/resource-api-issues.html)
with explicit fields, project restriction, ordering, `$top=100`, and `$skip`.
Filtering and pagination happen locally after a complete bounded scan. The scan
uses `TicketProperties.maxPages`, `maxObjects`, `maxHttpAttempts`, response-byte
budgets and the host request gate. An incomplete or oversized scan fails with a
limit error; it never produces a silently truncated filtered page. This favors
exact filter semantics for bounded projects and does not support arbitrarily
large project collections. Configured project keys must still match the remote
project. List and detail requests never request descriptions or comments.

Continuations are bound to the source configuration, project binding, credential
context and query. Revisions have kind `youtrack-live`, are mutable, and identify
that scope rather than a historical provider snapshot (`snapshot=false`). Every
call reads current remote data. Changes between calls can move, add or remove
results; an offset beyond the new result set fails as stale. Exact totals are
omitted. The host owns context expiry and authenticated cursor envelopes.

Detail and content use the stable database issue ID, validate the returned ID and
project, and use provider-owned navigation URLs. The separate
[`/api/issues/{issueID}` content request](https://www.jetbrains.com/help/youtrack/devportal/operations-api-issues.html)
returns the exact description as Markdown, with null mapped to empty text and a
1 MiB UTF-8 limit. It does not fetch comments, attachments, images, or external
links. `assets=false`; no asset adapter is registered. Markdown and untrusted
links still require the host's safe renderer. Content is transient and redacted
from its string representation; no description is added to observation records.

Native status is retained. Recognized fixed/done/resolved/verified states map to
success only when resolved; cancelled/canceled/won't-fix/duplicate/obsolete states
map to cancellation when resolved. Unknown workflow outcomes remain unknown.
HTTP fixtures cover the browser without live credentials or provider access.

## Explicit provisioning selectors

Issue commands accept NAME and EXTERNAL_ID version selectors resolved inside the
bound project's version bundle. Canonical IDs remain usable when names collide;
name selection requires a unique match. Legacy `fixVersions` strings keep their
name semantics, including numeric-looking names. Do not mix legacy values and
explicit selectors. IN_PROGRESS requests require an actual recognized progress
state; a missing state fails before mutation instead of falling back to OPEN.
