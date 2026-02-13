# EcoTaleIncome — Changelog

## v1.1.1 — 2026-02-13

### Bug Fixes
- **[CRITICAL] Fixed startup crash** — `NoClassDefFoundError: GameMode`. Replaced `setPermissionGroup(GameMode.Adventure)` with string-based API `setPermissionGroups("Adventure")`.
- **Fixed permissions for regular players** — `/income` command is now accessible in Adventure mode.

### Changes
- Added LuckPerms support (optional dependency).
- Three-tier permission check: LuckPerms API → permissions.json → wildcard matching.
- `PermissionHelper` — utility for reading server `permissions.json` with group and inheritance support.
