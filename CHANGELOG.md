# EcoTaleIncome — Changelog

## v1.3.0 — 2026-02-22

### Added
- Native ECS death detection system (`MobDeathSystem`) for mob kill rewards.
- Mob kill rewards now work with **MMOSkillTree**, **EndlessLeveling**, and any other level provider.
- RPG Leveling remains the preferred strategy (provides entity level data for level-scaled rewards).
- Automatic native ECS fallback when RPG Leveling is not installed.

---

## v1.2.6 — 2026-02-21

### Changed
- Updated README badges to current version.

---

## v1.2.5 — 2026-02-17

### Added
- Support for EndlessLeveling and MMOSkillTree level providers.
- Support for economy plugins: TheEconomy, HyEssentialsX, VaultUnlocked via EconomyAPI.

### Changed
- All providers use reflection without compile-time dependencies.
- `MMOSkillTreeProvider` caches ECS Store/Ref for performance.

---

## v1.2.4 — 2026-02-15

### Changed
- Refactored income logic in preparation for provider architecture.
- LuckPerms is now an optional dependency.

---

## v1.2.3 — 2026-02-13

### Added
- Level-scaled income based on player level (RPG Leveling).
- Support for 4 income sources: mobs, ores, wood, crops.
