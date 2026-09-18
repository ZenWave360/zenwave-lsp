# Publishing @zenwave360/lsp-js

The Gradle project version owns the release lifecycle. `npmVersion` defaults to
`0.1.0-next.0` for `0.1.0-SNAPSHOT`; CI generates `0.1.0-next.<run_number>.<run_attempt>`.
The same npm version is compiled into the server's `initialize.serverInfo.version`
and written into the package metadata before either transport is tested.

## Build locally

The VS Code platform features are integrated into the sibling `develop` branches.
Local builds can use those checkouts through Gradle composite builds:

```bash
./gradlew --no-daemon build npmPack -PnpmVersion=0.1.0-next.0
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

Dispatch `release.yml` from `main` with `version` (for example `0.1.0`
or `0.1.0-rc.1`), optional `developmentVersion`, and `publishNpm`.
Commit `release-notes/release-notes.v<VERSION>.md` before dispatching.
The shared Gradle release workflow validates the release, creates and merges
a version-bump PR, tags the immutable release commit, uploads the Kotlin
libraries to Maven Central, creates a GitHub release, and syncs `develop`.
Finish the Central deployment with Publish in the Central Portal.

The shared npm workflow checks out that same tag, builds and tests both
transports, verifies and smoke-tests the tarball, and optionally publishes it.
Stable versions use `latest`; release candidates use `next`. The npm and
Kotlin module versions share the root Gradle release version. There is no
manual build, packing, version commit or tag creation step.

## GitHub Actions and reuse

All three callers use pinned workflows from `ZenWave360/release-workflows`:

- `main.yml`: build JVM, Node and browser targets, collect Kover coverage,
  and publish coverage badges on main. Pull requests also build and test.
- `publish-npm-snapshots.yml`: publish `lsp-core` and `lsp-jvm` Maven
  snapshots, then build and publish the npm snapshot. Its filename is preserved
  for the existing npm trusted publisher. Manual dispatch can disable npm
  publication, leaving downloadable tarballs; Maven publication still runs.
- `release.yml`: the standard shared Gradle release lifecycle followed by
  the shared `npm-packages.yml` workflow.

The shared npm workflow owns build, artifact handoff and OIDC publication;
`scripts/npm-package.mjs`, its version tests and `scripts/npm-smoke.sh`
stay here because they verify this server's entry points and wire behavior.
CI uses `-PuseLocalDependencies=false` to resolve published Kotlin libraries.
The npm tarball bundles their JavaScript code. npm packages are not Kotlin
common metadata/KLIB dependencies.

### Maven Central prerequisites

Configure the same environments as the other ZenWave KMP repositories:

- `maven-central-snapshots`: allow develop/next for automatic snapshots.
- `maven-central-upload`: allow main and require approval for releases.

Each environment needs `CENTRAL_USERNAME`, `CENTRAL_TOKEN`, `SIGN_KEY`
and `SIGN_KEY_PASS`. Set these securely in GitHub; this repository cannot
retrieve another repository's secret values. npm environments contain no tokens.
The `badges` branch holds generated coverage SVGs.

Publish the integrated upstream snapshots before triggering LSP CI:

```bash
git -C ../dsl-kotlin push origin develop
# Wait for the DSL snapshot workflow to finish.
git -C ../zenwave-manifest push origin develop
# Wait for the manifest snapshot workflow to finish.
git push -u origin develop
```

In particular, the DSL artifact must contain `GenerateMermaidFromZdl`. The earlier
`@zenwave360/dsl@1.10.0-next.6.2` predates that integration. CI no longer checks out
a private DSL commit or requires a sibling directory.
