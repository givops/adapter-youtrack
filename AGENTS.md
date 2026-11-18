# Public YouTrack adapter contributor instructions

Follow CONTRIBUTING.md. Use an isolated task branch and worktree. Keep all new
content in English. Preserve public Java 25 / Spring Boot 4.0.9 compatibility;
never depend on private GivOps source, fixtures, credentials or unpublished parents.
Use synthetic provider fixtures and retain the pinned SDK contracts.

Before merging a PR, run `./scripts/verify-local.sh` on its final commit and
report the actual results. Build and test verification runs locally.
GitHub Actions runs package releases where configured and automatic CodeQL
security scans. Keep every removed hosted check in the local
verification entrypoint. Do not skip tests to obtain a successful verification.
