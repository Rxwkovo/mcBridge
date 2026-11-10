# Release MechAgent Bridge

GitHub is the distribution channel for v1.0. The supported build target is `:1.21.1-neoforge`. Use a clean checkout, Java 21 for the compile toolchain, and Node.js 22.16 or newer. CI runs Gradle on Java 25 with Java 21 installed for this target.

1. Set the stable version consistently in `gradle.properties`, `mcp-server/package.json`, and the lockfile. Update the changelog and `docs/releases/v<version>.md`. Open a PR and wait for the build checks. The `Build and verify` workflow also accepts a manual run. It builds and tests the mod, type-checks and tests the server, and uploads the JAR, MCP ZIP and SHA-256 manifest as one artifact.
2. From the final commit, run `./gradlew :1.21.1-neoforge:build`, then `npm ci`, `npm run typecheck` and `npm test` in `mcp-server/`. From the root run `node scripts/package-release.mjs v1.0.0` with no existing `release-assets/` directory. Check the metadata and smoke-test the artifact in an isolated full pack. Compare the JAR SHA-256 with the final main-branch build artifact. If the bytes differ, use the verified CI JAR for the smoke test and record its digest.
3. Create an annotated stable tag on the verified commit on `main`. Include a line exactly in the form `JAR SHA-256: <lowercase hex digest>`. Push only the requested tag to `Rxwkovo/mcBridge`.
4. The GitHub Release workflow builds and tests that tagged commit. It checks the tag, main ancestry, JAR metadata and the accepted digest before creating a draft. It compares downloaded release assets byte for byte before making the draft public. Rerun a failed workflow to retry a draft. A rerun against a public release verifies the assets in place. An unexpected asset or changed digest fails the run.
5. Check the public release, download each asset and verify `SHA256SUMS.txt`. Keep the upstream MIT notice with distributions. Keep installation media, credentials, real logs and worlds out of source and release assets.

Select the stable tag in the manual GitHub Release workflow to retry it. Use `Build and verify` to validate untagged code. Neither workflow uses a Modrinth, npm publishing or MCP Registry credential.
