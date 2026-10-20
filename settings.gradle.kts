rootProject.name = "Spyglass"

include("spyglass-api", "spyglass-core", "spyglass", "spyglass-velocity", "spyglass-importer")

// The Crusalis platform integration (Crusalis phase 5 §6). Optional at runtime, and
// optional at build time too: it resolves platform-sdk-api from a credential-free
// public repository, so a fork builds it without a Crusalis token, and
// -PwithoutCrusalisPlatform skips it entirely for anyone who would rather not.
if (!providers.gradleProperty("withoutCrusalisPlatform").isPresent) {
    include("spyglass-platform")
}
