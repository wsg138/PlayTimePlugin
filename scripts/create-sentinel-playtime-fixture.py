#!/usr/bin/env python3
"""Create the disposable legacy SQLite fixture used by Enthusia Sentinel.

The fixture deliberately represents an established PlayTime installation whose
lifetime_agg table predates the last_seen column. It contains all tables required
by the current fail-safe startup guard so the real plugin must migrate the column
without replacing the database or losing canary rows.
"""

from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

PLAYER_UUID = "00000000-0000-0000-0000-000000000001"
BATCH_UUID = "11111111-1111-1111-1111-111111111111"


def create_fixture(destination: Path) -> None:
    destination = destination.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.unlink(missing_ok=True)

    with sqlite3.connect(destination) as connection:
        create_schema(connection)
        insert_canaries(connection)
        connection.commit()
        connection.execute("VACUUM")

    validate_fixture(destination)


def create_schema(connection: sqlite3.Connection) -> None:
    connection.executescript("PRAGMA user_version = 0; PRAGMA journal_mode = DELETE;")
    create_aggregation_schema(connection)
    create_profile_schema(connection)


def create_aggregation_schema(connection: sqlite3.Connection) -> None:
    connection.executescript(
        """
        CREATE TABLE daily_agg (
          player_uuid TEXT NOT NULL,
          day DATE NOT NULL,
          active_minutes INTEGER NOT NULL DEFAULT 0,
          afk_minutes INTEGER NOT NULL DEFAULT 0,
          total_minutes INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY (player_uuid, day)
        );

        CREATE TABLE hourly_agg (
          player_uuid TEXT NOT NULL,
          hour_start TIMESTAMP NOT NULL,
          active_minutes INTEGER NOT NULL DEFAULT 0,
          afk_minutes INTEGER NOT NULL DEFAULT 0,
          total_minutes INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY (player_uuid, hour_start)
        );

        CREATE TABLE lifetime_agg (
          player_uuid TEXT PRIMARY KEY,
          first_join TIMESTAMP NOT NULL,
          last_join TIMESTAMP NOT NULL,
          active_minutes INTEGER NOT NULL DEFAULT 0,
          afk_minutes INTEGER NOT NULL DEFAULT 0,
          total_minutes INTEGER NOT NULL DEFAULT 0
        );

        CREATE TABLE joins_log (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          player_uuid TEXT NOT NULL,
          joined_at TIMESTAMP NOT NULL
        );
        """
    )


def create_profile_schema(connection: sqlite3.Connection) -> None:
    connection.executescript(
        """
        CREATE TABLE player_profiles (
          player_uuid TEXT PRIMARY KEY,
          username TEXT NOT NULL,
          display_name TEXT,
          first_seen TIMESTAMP NOT NULL,
          last_seen TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL
        );

        CREATE TABLE player_skin_profiles (
          player_uuid TEXT PRIMARY KEY,
          texture_value TEXT,
          texture_signature TEXT,
          last_known_name TEXT,
          updated_at TIMESTAMP NOT NULL
        );

        CREATE TABLE playtime_applied_batches (
          batch_id TEXT PRIMARY KEY,
          applied_at TIMESTAMP NOT NULL
        );
        """
    )


def insert_canaries(connection: sqlite3.Connection) -> None:
    connection.execute(
        "INSERT INTO daily_agg VALUES (?, ?, ?, ?, ?)",
        (PLAYER_UUID, "2026-01-02", 321, 45, 366),
    )
    connection.execute(
        "INSERT INTO hourly_agg VALUES (?, ?, ?, ?, ?)",
        (PLAYER_UUID, "2026-01-02 12:00:00", 60, 5, 65),
    )
    connection.execute(
        """INSERT INTO lifetime_agg
           (player_uuid, first_join, last_join, active_minutes, afk_minutes, total_minutes)
           VALUES (?, ?, ?, ?, ?, ?)""",
        (
            PLAYER_UUID,
            "2025-06-01 12:00:00",
            "2026-01-02 12:34:56",
            1234,
            56,
            1290,
        ),
    )
    insert_profile_canaries(connection)


def insert_profile_canaries(connection: sqlite3.Connection) -> None:
    connection.execute(
        "INSERT INTO joins_log (id, player_uuid, joined_at) VALUES (?, ?, ?)",
        (1, PLAYER_UUID, "2026-01-02 12:34:56"),
    )
    connection.execute(
        "INSERT INTO player_profiles VALUES (?, ?, ?, ?, ?, ?)",
        (
            PLAYER_UUID,
            "sentineluser",
            "Sentinel User",
            "2025-06-01 12:00:00",
            "2026-01-02 12:34:56",
            "2026-01-02 12:34:56",
        ),
    )
    connection.execute(
        "INSERT INTO player_skin_profiles VALUES (?, ?, ?, ?, ?)",
        (
            PLAYER_UUID,
            "sentinel-texture-canary",
            "sentinel-signature-canary",
            "sentineluser",
            "2026-01-02 12:34:56",
        ),
    )
    connection.execute(
        "INSERT INTO playtime_applied_batches VALUES (?, ?)",
        (BATCH_UUID, "2026-01-02 12:34:56"),
    )


def validate_fixture(destination: Path) -> None:
    if not destination.is_file() or destination.stat().st_size <= 0:
        raise SystemExit(f"fixture was not created correctly: {destination}")


def main(argv: list[str]) -> None:
    if len(argv) != 2:
        raise SystemExit("usage: create-sentinel-playtime-fixture.py OUTPUT.db")
    create_fixture(Path(argv[1]))


if __name__ == "__main__":
    main(sys.argv)
