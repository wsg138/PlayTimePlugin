# /seen observed username history

- SEEN-01: When a player profile is observed, the plugin shall retain that username under the player's UUID with first and last observation times, even after a rename or restart.
- SEEN-02: When upgrading an existing database, the plugin shall retain its current profile names as observed history without inventing earlier names.
- SEEN-03: When `/seen` targets a known player, the plugin shall show last-seen status and prior observed names; a lookup by an unambiguous prior name shall resolve the same UUID.
- SEEN-04: When a name is unknown or ambiguous, the plugin shall give a clear result without blocking the server thread or creating a player record.
- SEEN-05: While storage is unavailable, the plugin shall report lookup failure rather than claim the player has never joined.
- SEEN-06: When a player is hidden from the requester, `/seen` shall not reveal their online presence.

History is server-observed only. UUID is the identity key for Java and Floodgate players alike. The existing profile column remains the current-name source for other plugin features.
