# regression/bot/ — reserved for a mineflayer driver

Intentionally empty. The current `run.py` harness seeds Mongo directly and
drives queries over RCON. That covers query/storage/render regressions.

Event-extractor regressions (what a real `BlockBreakEvent` produces) are
currently covered by unit tests in `plugin/src/test/java/.../listener/...`.
When live-event reproducibility becomes important (e.g. for WorldEdit/FAWE
integration in Block 4), drop a mineflayer-based scenario runner here:

```
regression/bot/
├── package.json
├── scenario-basic.js     # connect, break/place/chat, disconnect
├── scenario-rollback.js  # seed a chest, break it, logout
└── scenario-worldedit.js # //set over a chest
```

And wire it into `run.py` as an extra phase.
