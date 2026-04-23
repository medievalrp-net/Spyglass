#!/usr/bin/env python3
"""Seed deterministic test records into both v1 (v1) and Spyglass (v2)."""
import argparse
import uuid
from datetime import datetime, timedelta, timezone

from pymongo import MongoClient

# Fixed UUIDs so reruns are idempotent against the tag filter.
ALICE = uuid.UUID("11111111-1111-1111-1111-111111111111")
BOB = uuid.UUID("22222222-2222-2222-2222-222222222222")
WORLD = uuid.UUID("77777777-7777-7777-7777-777777777777")
TEST_TAG = "sg-regression"


def v2_record(event, target, source_uuid, source_name, x, y, z, extras=None,
              original_material="STONE", new_material="AIR", env=False,
              env_description=None):
    now = datetime.now(timezone.utc)
    if env:
        origin = {"kind": "environment", "detail": env_description or "test"}
        source = {
            "kind": "environment",
            "playerId": None,
            "playerName": None,
            "entityId": None,
            "entityType": None,
            "pluginName": None,
            "commandBlockLocation": None,
            "description": env_description or "test",
        }
    else:
        origin = {"kind": "player", "detail": None}
        source = {
            "kind": "player",
            "playerId": source_uuid,
            "playerName": source_name,
            "entityId": None,
            "entityType": None,
            "pluginName": None,
            "commandBlockLocation": None,
            "description": None,
        }
    doc = {
        "id": uuid.uuid4(),
        "schemaVersion": 1,
        "event": event,
        "occurred": now,
        "expiresAt": now + timedelta(weeks=4),
        "origin": origin,
        "source": source,
        "location": {
            "worldId": WORLD,
            "worldName": "world",
            "x": x,
            "y": y,
            "z": z,
        },
        "target": target,
        "_regressionTag": TEST_TAG,
    }
    if event in ("break", "place", "decay", "form", "grow", "ignite"):
        doc["originalBlock"] = v2_snapshot(original_material)
        doc["newBlock"] = v2_snapshot(new_material)
    if event == "say":
        doc["message"] = extras.get("message")
        doc["recipients"] = []
    if event == "command":
        doc["commandLine"] = extras.get("commandLine")
    if event == "join":
        doc["address"] = extras.get("address", "127.0.0.1")
    return doc


def v2_snapshot(material):
    return {
        "material": material,
        "blockData": f"minecraft:{material.lower()}",
        "containerItems": [],
        "signFront": [],
        "signBack": [],
        "bannerPatterns": [],
        "jukeboxRecord": None,
    }


def v1_record(event, target, player_uuid, x, y, z):
    """Minimal v1-schema document covering the event shapes we compare against.

    Intentionally small: we only seed break / place so that v1/v2 comparison
    checks the common path. Extending to other events would require more
    knowledge of v1's per-event field layout.
    """
    now = datetime.now(timezone.utc)
    return {
        "Event": event,
        "Created": now,
        "Expires": now + timedelta(weeks=4),
        "Player": str(player_uuid),
        "Target": target,
        "Location": {
            "X": x,
            "Y": y,
            "Z": z,
            "World": str(WORLD),
        },
        "_regressionTag": TEST_TAG,
    }


def clear_existing(db, collection, tag):
    # Sweep both current-tagged records and any stale fixture data left behind
    # by earlier manual seeding (fixed test UUIDs).
    query = {
        "$or": [
            {"_regressionTag": tag},
            {"source.playerId": ALICE},
            {"source.playerId": BOB},
            {"Player": {"$in": [str(ALICE), str(BOB)]}},
        ]
    }
    before = db[collection].count_documents(query)
    db[collection].delete_many(query)
    return before


def seed_v2(client, *, skip=False):
    if skip:
        return 0, 0
    db = client["Spyglass"]
    removed = clear_existing(db, "EventRecords", TEST_TAG)
    docs = [
        v2_record("break", "STONE", ALICE, "Alice", 10, 64, 10, original_material="STONE"),
        v2_record("break", "DIRT", ALICE, "Alice", 11, 64, 10, original_material="DIRT"),
        v2_record("place", "GLASS", ALICE, "Alice", 12, 64, 10,
                  original_material="AIR", new_material="GLASS"),
        v2_record("break", "IRON_ORE", BOB, "Bob", 20, 40, 20, original_material="IRON_ORE"),
        v2_record("say", "Alice", ALICE, "Alice", 0, 64, 0, extras={"message": "hello regression"}),
        v2_record("join", "Alice", ALICE, "Alice", 0, 64, 0, extras={"address": "127.0.0.1"}),
        # environment events
        v2_record("decay", "OAK_LEAVES", None, None, 5, 70, 5, env=True,
                  env_description="leaves-decay", original_material="OAK_LEAVES"),
        v2_record("decay", "OAK_LEAVES", None, None, 6, 70, 5, env=True,
                  env_description="leaves-decay", original_material="OAK_LEAVES"),
        v2_record("grow", "OAK_LOG", None, None, 3, 70, 3, env=True,
                  env_description="structure-grow:OAK", original_material="AIR",
                  new_material="OAK_LOG"),
        v2_record("form", "SNOW", None, None, 0, 75, 0, env=True,
                  env_description="block-form", original_material="AIR",
                  new_material="SNOW"),
        v2_record("ignite", "FIRE", None, None, 30, 65, 30, env=True,
                  env_description="ignite:LAVA", original_material="AIR",
                  new_material="FIRE"),
    ]
    db["EventRecords"].insert_many(docs)
    return removed, len(docs)


def seed_v1(client, *, skip=False):
    if skip:
        return 0, 0
    db = client["v1"]
    removed = clear_existing(db, "DataEntry", TEST_TAG)
    docs = [
        v1_record("break", "STONE", ALICE, 10, 64, 10),
        v1_record("break", "DIRT", ALICE, 11, 64, 10),
        v1_record("place", "GLASS", ALICE, 12, 64, 10),
        v1_record("break", "IRON_ORE", BOB, 20, 40, 20),
    ]
    db["DataEntry"].insert_many(docs)
    return removed, len(docs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mongo", default="mongodb://localhost:27017")
    parser.add_argument("--skip-v1", action="store_true")
    parser.add_argument("--skip-v2", action="store_true")
    args = parser.parse_args()

    client = MongoClient(args.mongo, uuidRepresentation="standard")
    v2_removed, v2_added = seed_v2(client, skip=args.skip_v2)
    v1_removed, v1_added = seed_v1(client, skip=args.skip_v1)
    print(f"v2 EventRecords: removed={v2_removed}, added={v2_added}")
    print(f"v1 DataEntry : removed={v1_removed}, added={v1_added}")


if __name__ == "__main__":
    main()
