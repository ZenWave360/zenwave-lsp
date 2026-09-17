# Publishing @zenwave360/lsp-js

The Gradle project version owns the release lifecycle. `npmVersion` defaults to
`0.1.0-next.0` for `0.1.0-SNAPSHOT`; CI generates `0.1.0-next.<run_number>.<run_attempt>`.
The same npm version is compiled into the server's `initialize.serverInfo.version`
and written into the package metadata before either transport is tested.

## Build locally

The DSL checkout must contain commit `835a75fdb7b798fa382f90fb3c109de74d1e1115`
(the `vscode-platform/dsl` worktree), including `GenerateMermaidFromZdl`.
Until that work is integrated into the usual DSL checkout, build against its worktree:

```bash
./gradlew --no-daemon build npmPack -PnpmVersion=0.1.0-next.0 \
  -PuseLocalDependencies=false -Pzenwave.local.dslKotlinDir=../dsl-kotlin-vscode-platform
node scripts/npm-package.mjs verify 0.1.0-next.0
```

Windows PowerShell uses `./gradlew.bat` with the same arguments.
The tarball is `build/npm/zenwave360-lsp-js-0.1.0-next.0.tgz`. Gradle generates
the DSL grammar, compiles Kotlin, bundles both entry points, copies documentation,
and invokes npm packing automatically. `build` tests JVM, Node, and the browser;
Chrome or Edge is required for the browser checks.

If a first manual publication is needed:

```bash
npm login --registry=https://registry.npmjs.org/
npm publish ./build/npm/zenwave360-lsp-js-0.1.0-next.0.tgz --access public --tag next
```

Do not republish an existing version. This package already had `0.1.0-next.0`
on the registry when this workflow was configured.

## Trusted publisher

After logging in with an account that has write access and 2FA enabled, run this
in an interactive Git Bash terminal with npm 11.15 or newer:

```bash
bash scripts/npm-trust.sh
```

By default this creates a publisher with repository `ZenWave360/zenwave-lsp`, caller workflow
`publish-npm-snapshots.yml`, environment `npm-snapshots`, and permission to run
`npm publish`. The script leaves output attached to the terminal so npm can
complete browser 2FA. If an obsolete publisher exists, inspect it with
`npm trust list @zenwave360/lsp-js` and revoke that specific ID before creating
the replacement. The script does not delete other publisher entries.

Create the `npm-snapshots` GitHub environment and allow deployment from `develop`
and `next`. No npm token is needed: the publish job has `id-token: write` and uses
OIDC with provenance.

For releases, add the separate publisher without recreating the snapshot entry:

```bash
bash scripts/npm-trust.sh release
```

It trusts `ZenWave360/zenwave-lsp`, workflow `release.yml`, environment `npm-publish`.
`bash scripts/npm-trust.sh all` creates both entries when neither has been configured.
Create the `npm-publish` GitHub environment, allowing tags matching `v*` and the
`main` branch for manual dispatch. Add required reviewers if releases need approval.

## Release versions

`release.yml` runs when a `v*` tag is pushed. It validates that the tagged commit
is integrated into `main` and that the root Gradle version exactly matches the
tag without its `v` prefix. A SNAPSHOT build cannot be published as a release.
The same `npm-package.yml` builds, tests, verifies, and publishes the tarball:

| Root Gradle version | Git tag | npm version | npm tag |
| --- | --- | --- | --- |
| `0.1.0` | `v0.1.0` | `0.1.0` | `latest` |
| `0.1.0-rc.1` | `v0.1.0-rc.1` | `0.1.0-rc.1` | `next` |

To release `0.1.0`, first integrate the LSP work into `main`. In a release PR,
set the root `build.gradle.kts` line to `version = "0.1.0"` and commit it as
`chore(release): release 0.1.0`. All LSP modules inherit that version. After the
PR is merged, tag the exact release commit from your interactive terminal:

```bash
git fetch origin main
# Confirm origin/main is the commit whose Gradle version is 0.1.0.
git tag -a v0.1.0 origin/main -m "Release 0.1.0"
git push origin v0.1.0
```

There is no manual build or packing step. For a release candidate, use
`0.1.0-rc.1` in both the Gradle version and tag. To retry an existing release tag
or build without publishing, dispatch `release.yml` from `main`, entering the
version without `v`. Disable `publishNpm` for an artifact-only build.

Once the tag exists, prepare the next development version (for example,
`0.1.1-SNAPSHOT`) in a follow-up PR and sync it into `develop`. The release caller
does not create version-bump PRs, Git tags, GitHub releases, or Maven publications.
Both callers currently use the pinned DSL library and the same parser/manifest
snapshot dependencies; the npm artifact bundles their code into its entry points.

## GitHub Actions and reuse

Push `develop` or `next` to run `publish-npm-snapshots.yml`. Manual dispatch has a
`publishNpm` switch; turn it off to build and download the tarball without publishing.

The caller contains triggers, branch policy, publisher environment, and a pinned
DSL source revision. `npm-package.yml` is a local reusable prototype containing
the build, verification, artifact handoff, and OIDC publish jobs. It resolves
JSON parser and manifest dependencies from Maven Central snapshots, while checking
out and building the pinned DSL library needed by lsp-core. It does not depend on sibling
directories existing on the runner.

The pinned DSL worktree commit was not yet on GitHub when this was configured.
Publish that existing branch before running the LSP workflow:

```bash
git -C ../dsl-kotlin push origin vscode-platform/dsl
git push -u origin develop
```

Pushing the DSL branch makes the pinned source accessible; it does not merge or
release that branch. Its later integration remains work in dsl-kotlin.

After this prototype passes in GitHub, extract the common jobs into
`ZenWave360/release-workflows`, making package name, dependency source checkouts,
Gradle build arguments, artifact path, and verification command inputs. Keep
repository-specific bundling and version generation in Gradle. The caller then
changes its `uses:` reference; npm continues trusting the caller workflow in
`zenwave-lsp`, so extraction does not require changing the trusted publisher.

The shared `release-gradle.yml` currently also requires Maven Central publication
(`publishToMavenCentral`) and does not accept LSP's pinned DSL checkout/build
arguments. LSP does not configure that Central task yet. Once the shared workflow
supports this build and the desired publication targets, let it prepare versions
and tags and call the same reusable npm build/publish jobs. Keep `release.yml`
as the repository's trusted-publisher caller instead of copying the complete
release preparation implementation into this repository.
