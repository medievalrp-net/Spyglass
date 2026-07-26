# Publishing spyglass-api to Maven Central

Only `spyglass-api` is published (Apache-2.0). The GPL modules
(`spyglass-core`, `spyglass`, `spyglass-velocity`) stay unpublished internals.

Coordinates: `net.medievalrp:spyglass-api:<version>`, where `<version>` is the
project version in `gradle.properties`, so the library tracks each release.

## How a release publishes it

`.github/workflows/release.yml` runs on a version bump to `main`. After it cuts
the GitHub release, the `publish-maven-central` job runs
`./gradlew publishAggregationToCentralPortal`, which builds, signs, and uploads
the artifact. The job is skipped (not failed) until the credentials below are
set, so nothing here changes the release flow before setup is done.

`publishingType` (root `build.gradle.kts`) is `"AUTOMATIC"`, so a version bump
reaching `main` is the only gate. Central deployments are immutable once
published, so a bad version can't be withdrawn, only superseded.

`publishingTimeout` next to it is load-bearing, not decoration. nmcp defaults it
to zero, and its AUTOMATIC path only waits for `PUBLISHED` when the timeout is
positive. Leave it unset and the task returns success as soon as Central has
*validated* the bundle, having merely asked for publication. A green job would
then mean "validated", while everyone reading it assumes "released".

That distinction is the whole bug. `publishingType` used to be `"USER_MANAGED"`,
which staged the deployment and waited for someone to click **Publish** in the
Portal. Nobody did, the job reported success either way, and 1.0.8, 1.0.9 and
1.0.10 never reached Central while it kept serving 1.0.7 from 2026-07-08.

So: whatever you change here, make sure a green job cannot mean anything short
of published. If you set `publishingType` back to `"USER_MANAGED"`, add a check
that fails on a validated-but-unpublished deployment. If you drop
`publishingTimeout`, you have re-created the same hole one step further along.

## One-time setup

Done once, by a human. Until it is finished the publish job stays skipped.

### 1. Central Portal account and namespace

1. Sign in at [central.sonatype.com](https://central.sonatype.com) (GitHub works).
2. Register the namespace `net.medievalrp`. The Portal gives you a TXT record;
   add it to the `medievalrp.net` DNS (Cloudflare) and verify. This proves
   domain ownership and reserves the group id.
3. Generate a user token (Account -> Generate User Token). It returns a
   username and password pair.

### 2. GPG signing key

Central rejects unsigned artifacts.

```bash
gpg --full-generate-key                       # RSA 4096, no expiry is fine
gpg --list-secret-keys --keyid-format=long    # note the KEYID
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>   # publish the public key
gpg --armor --export-secret-keys <KEYID>      # the armored private key for CI
```

Central verifies signatures against the public key on the keyserver, so the
`--send-keys` step is required.

### 3. GitHub Actions secrets

Repo -> Settings -> Secrets and variables -> Actions -> New repository secret:

| Secret | Value |
|--------|-------|
| `CENTRAL_PORTAL_USERNAME` | username from the Portal user token |
| `CENTRAL_PORTAL_PASSWORD` | password from the Portal user token |
| `SIGNING_KEY` | the full armored private key block from `--export-secret-keys` (keep the newlines) |
| `SIGNING_PASSWORD` | the key's passphrase |

## Checking it locally (optional)

No account needed.

```bash
# Inspect the generated POM and artifacts.
./gradlew :spyglass-api:publishApiPublicationToLocalRepository
ls build/repo/net/medievalrp/spyglass-api/

# Stage the exact bundle CI would upload. Set the signing env first to include
# the .asc signatures; without it the bundle is unsigned (fine for a dry run).
export SIGNING_KEY="$(gpg --armor --export-secret-keys <KEYID>)"
export SIGNING_PASSWORD=...
./gradlew nmcpZipAggregation
unzip -l build/nmcp/zip/aggregation.zip
```
