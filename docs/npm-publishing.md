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

This creates a publisher with repository `ZenWave360/zenwave-lsp`, caller workflow
`publish-npm-snapshots.yml`, environment `npm-snapshots`, and permission to run
`npm publish`. The script leaves output attached to the terminal so npm can
complete browser 2FA. If an obsolete publisher exists, inspect it with
`npm trust list @zenwave360/lsp-js` and revoke that specific ID before creating
the replacement. The script does not delete other publisher entries.

Create the `npm-snapshots` GitHub environment and allow deployment from `develop`
and `next`. No npm token is needed: the publish job has `id-token: write` and uses
OIDC with provenance.

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

This workflow publishes npm snapshots only. Maven Central publication and stable
release dispatch are not configured by it; a future `release.yml` caller should
use the shared release lifecycle and a separate `npm-publish` publisher entry.
