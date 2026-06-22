# Licensing

Spyglass is open source under a split license. The public extension API is
permissive so other plugins can build on it; the plugin and its internals are
copyleft so the engine stays open.

Copyright (c) 2026 MedievalRP.

## Per-module licenses

| Module | License | Role |
|--------|---------|------|
| `spyglass-api` | Apache License 2.0 ([spyglass-api/LICENSE](spyglass-api/LICENSE)) | The public contract third-party plugins depend on. Permissive so integrations stay unencumbered. |
| `spyglass-core` | GNU GPL v3.0 ([LICENSE](LICENSE)) | Shared internals: storage codecs and record-store backends. |
| `spyglass` | GNU GPL v3.0 ([LICENSE](LICENSE)) | The Paper plugin: listeners, ingest pipeline, rollback, commands. |
| `spyglass-velocity` | GNU GPL v3.0 ([LICENSE](LICENSE)) | The read-only Velocity proxy companion. |

If you only build against `spyglass-api`, you are working under Apache-2.0, and
the GPL does not reach your plugin.
