# GivOps YouTrack adapter

A standalone Java library providing YouTrack ticketing reads, version membership,
and project, version and issue creation through the public [GivOps adapter
SDK](https://github.com/givops/adapter-sdk). No private GivOps source is needed to
build or test it. Licensed under [Apache-2.0](LICENSE).

## Compatibility and scope

- Java 25, Spring Boot 4.0.9 and Jackson 3.
- Maven artifact: `io.givops:adapter-youtrack:0.1.0-SNAPSHOT`.
- SDK API: `0.1`. Artifact version, SDK compatibility, YouTrack product version,
  and API profile are separate identifiers.
- Provider: `YOUTRACK`; API profile: `youtrack-rest-api`, using the current `/api`
  REST dialect at an HTTPS origin or one context path, such as
  `https://youtrack.example.test/youtrack`.
- Capabilities: `ticketing.read`, `ticketing.version-membership`, `ticketing.write`.
  The library does not implement report or release-command adapters.

The adapter does not claim a tested YouTrack server-version range. An unknown
product version does not disable reads. Optional product-version inspection is
not implemented; compatibility inspection therefore remains unknown. Unit and
HTTP fixture tests make no live YouTrack requests.

The provider definition recognizes `/issue/KEY-123` URLs with an optional single
context path and title slug. The SDK's key grammar stays fixed at API `0.1`;
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
