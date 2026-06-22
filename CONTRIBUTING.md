# Contributing to Spyglass

Thanks for wanting to help. Spyglass welcomes bug reports, fixes, and features
across all four modules (`spyglass-api`, `spyglass-core`, `spyglass`, and
`spyglass-velocity`).

## Ground rules

- Open an issue first for anything non-trivial, so we can agree on the approach
  before you write code.
- One logical change per pull request. Keep the diff focused.
- Non-trivial code changes ship with a test in the same pull request, and
  `./gradlew check` must pass.
- Match the surrounding code style. Use the plugin logger; no `printStackTrace`,
  `System.out`, or `System.err`.
- Plain hyphens only. No em or en dashes in code, comments, docs, or commit
  messages.

See `README.md` for architecture and `CLAUDE.md` for the module layout and the
hard rules (event-type parity, tests-with-code, listener contract) before
starting in a new area.

## How to submit

1. Fork the repository and create a branch for your change.
2. Build and test locally with `./gradlew build`.
3. Open a pull request with a clear description of what changed and why.

## Contributor License Agreement

Before your first contribution can be merged, you agree to the Spyglass
Contributor License Agreement in [CLA.md](CLA.md).

In plain English: **you keep ownership of your work, and you grant the Maintainer
(MedievalRP) a broad, irrevocable license to use it, including the right to
license the Project, and your contribution as part of it, under any terms now or
in the future (open-source, source-available, or commercial).** This is what lets
the Project stay sustainable and offer commercial licensing without tracking down
every contributor for permission. You remain free to use your own contribution
however you like elsewhere.

By opening a pull request you confirm that you have read and agree to the CLA for
every contribution in it. A signing bot may be added later to record agreement
automatically; until then, your pull request is your agreement.

If you are contributing on behalf of an employer, make sure you have permission
to do so.
