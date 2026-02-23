<div align="center">

# 💰 EcoTaleIncome

### Earn currency through gameplay on your Hytale server

Reward players for **mob kills**, **mining**, **woodcutting** & **farming** — with RPG level-scaling, VIP multipliers, and a pluggable economy system.

![Hytale Server Mod](https://img.shields.io/badge/Hytale-Server%20Mod-0ea5e9?style=for-the-badge)
![Version](https://img.shields.io/badge/version-1.3.0-10b981?style=for-the-badge)
![Java](https://img.shields.io/badge/Java-17+-f97316?style=for-the-badge&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-a855f7?style=for-the-badge)
![Ecotale](https://img.shields.io/badge/Ecotale-1.0.7-6366f1?style=for-the-badge)

<br>

[**Getting Started**](#-getting-started) •
[**Configuration**](#%EF%B8%8F-configuration) •
[**Commands**](#-commands) •
[**API**](#-economy-api) •
[**Contributing**](#-contributing)

</div>

---

## ✨ Features

| Feature | Description |
|:--------|:------------|
| ⚔️ **Mob Kills** | 7 reward tiers from Critter to World Boss with dynamic level-scaling |
| ⛏️ **Ore Mining** | 12 ore types with depth bonus — the deeper you mine, the more you earn |
| 🪓 **Woodcutting** | 18+ tree types from Softwood to Ebony |
| 🌾 **Farming** | 15 crop types, F-key + LMB harvesting (configurable) |
| 📈 **RPG Leveling** | Optional integration — higher level difference = bigger multiplier |
| 👑 **VIP Multipliers** | Permission-based ×1.25 / ×1.50 reward multipliers |
| 🛡️ **Anti-Farm** | Rate limits, diminishing returns, block cooldowns, placed-block exploit protection |
| 🔌 **Pluggable Economy** | Built-in Ecotale support + API for any economy plugin |
| 🔧 **Hot Reload** | `/income reload` — no restart needed |
| 🌍 **Localization** | RU / EN with per-player language switching |

## 📦 Requirements

| Dependency | Version | Required | Description |
|:-----------|:--------|:--------:|:------------|
| [Ecotale](https://curseforge.com/hytale/mods/ecotale) | ≥ 1.0.0 | ✅ | Economy & currency system |
| [RPG Leveling](https://curseforge.com/hytale/mods/rpg-leveling) | ≥ 0.2.0 | ❌ | Level-scaling for mob rewards |

> [!TIP]
> Don't use Ecotale? Any economy plugin can be connected — see [Economy API](#-economy-api) or the built-in [Generic Adapter](#generic-economy-no-code-adapter).

## 🚀 Getting Started

```bash
# 1. Download the latest release
# 2. Drop into your server's mods/ folder
cp EcoTaleIncome-1.2.5.jar /server/mods/

# 3. Start the server — config is created automatically
# 4. Edit the config to your liking
nano mods/com.crystalrealm_EcoTaleIncome/EcoTaleIncome.json
```

**That's it.** All modules are enabled by default with balanced reward values.

## 🎮 Commands

| Command | Description | Permission |
|:--------|:------------|:-----------|
| `/income` | Current status & balance | `ecotaleincome.command.income` |
| `/income info` | Plugin info & active modules | `ecotaleincome.command.info` |
| `/income stats` | Your reward statistics | `ecotaleincome.command.stats` |
| `/income stats <player>` | Other player's stats | `ecotaleincome.command.stats.others` |
| `/income reload` | Reload configuration | `ecotaleincome.admin.reload` |
| `/income debug` | Toggle debug mode | `ecotaleincome.admin.debug` |
| `/income lang` | Show language usage hint | — |
| `/income langen` | Switch to English | — |
| `/income langru` | Switch to Russian | — |
| `/income help` | Command reference | — |

## 🔐 Permissions

<details>
<summary><b>Base Permissions</b> — all players</summary>

```yaml
ecotaleincome.earn.mob         # Earn from mob kills
ecotaleincome.earn.ore         # Earn from mining
ecotaleincome.earn.wood        # Earn from woodcutting
ecotaleincome.earn.crop        # Earn from farming
ecotaleincome.command.income   # /income
ecotaleincome.command.info     # /income info
ecotaleincome.command.stats    # /income stats
```
</details>

<details>
<summary><b>VIP / Premium</b></summary>

```yaml
ecotaleincome.multiplier.vip       # ×1.25 reward multiplier
ecotaleincome.multiplier.premium   # ×1.50 reward multiplier
```
</details>

<details>
<summary><b>Admin</b></summary>

```yaml
ecotaleincome.admin.reload          # /income reload
ecotaleincome.admin.debug           # /income debug
ecotaleincome.command.stats.others  # /income stats <player>
ecotaleincome.*                     # All permissions
```
</details>

## ⚙️ Configuration

Config file: `mods/com.crystalrealm_EcoTaleIncome/EcoTaleIncome.json`

### Reward Tables

<details>
<summary><b>⚔️ Mob Tiers</b></summary>

| Tier | Reward | Examples |
|:-----|:-------|:---------|
| `CRITTER` | 0.5 – 1.5 | Small animals |
| `PASSIVE` | 1.0 – 3.0 | Cows, sheep |
| `HOSTILE` | 3.0 – 8.0 | Zombies, skeletons |
| `ELITE` | 8.0 – 15.0 | Elite mobs |
| `MINIBOSS` | 15.0 – 30.0 | Mini-bosses |
| `BOSS` | 30.0 – 75.0 | Bosses |
| `WORLDBOSS` | 75.0 – 200.0 | World bosses |

Per-entity overrides:
```json
"EntityOverrides": {
  "Dragon_Fire":  { "Min": 150.0, "Max": 300.0 },
  "Dragon_Frost": { "Min": 150.0, "Max": 300.0 }
}
```
</details>

<details>
<summary><b>⛏️ Ores & Depth Bonus</b></summary>

| Ore | Reward |
|:----|:-------|
| Copper / Coal | 0.5 – 1.5 |
| Iron | 1.5 – 3.0 |
| Silver | 2.0 – 4.0 |
| Gold | 3.0 – 6.0 |
| Cobalt | 4.0 – 8.0 |
| Thorium | 5.0 – 10.0 |
| Adamantite / Mithril | 8.0 – 15.0 |
| Onyxium | 10.0 – 20.0 |
| Diamond | 12.0 – 25.0 |
| Emerald | 6.0 – 12.0 |

**Depth Bonus:** +0.5% per block below Y=64, up to +50% max.
</details>

<details>
<summary><b>🪓 Trees</b></summary>

| Tree | Reward |
|:-----|:-------|
| Softwood / Fir / Oak / Birch / Spruce / Pine | 0.3 – 0.8 |
| Hardwood / Ash / Acacia / Willow / Palm / Jungle | 0.5 – 1.0 |
| Mahogany / Kweebec | 0.8 – 1.5 |
| Ebony | 1.0 – 2.0 |
</details>

<details>
<summary><b>🌾 Crops</b></summary>

| Crop | Reward |
|:-----|:-------|
| Wheat / Carrot / Potato | 0.3 – 0.8 |
| Beet / Onion | 0.4 – 1.0 |
| Tomato / Corn / Cotton | 0.5 – 1.2 |
| Pumpkin / Melon | 0.6 – 1.5 |
| Berry | 0.8 – 2.0 |
| Herb | 1.0 – 2.5 |
| Mushroom | 0.8 – 1.8 |

**Harvest Method** — set `"HarvestMethod"` in the `Farming` section:

| Value | Description |
|:------|:------------|
| `"use"` | F-key only |
| `"break"` | Left-click only |
| `"both"` | Both (default) |
</details>

### RPG Level Scaling

When **RPG Leveling** is installed, mob rewards scale based on the level difference between the player and the mob:

| Level Difference | Multiplier | |
|:-----------------|:-----------|:--|
| Mob 15+ levels below | ×0.10 | 🟥 Trivial |
| Mob 10–14 below | ×0.40 | 🟧 Low |
| Mob 5–9 below | ×0.70 | 🟨 Below |
| Within ±4 levels | ×1.00 | 🟩 Equal |
| Mob 5–9 above | ×1.30 | 🟦 Above |
| Mob 10–14 above | ×1.60 | 🟪 Hard |
| Mob 15+ above | ×2.00 | ⬛ Maximum |

### Economy Provider

```json
"General": {
  "EconomyProvider": "ecotale"
}
```

Built-in providers: `"ecotale"`, `"generic"`. Custom providers can be registered via the [Economy API](#-economy-api).

### Generic Economy (No-Code Adapter)

For server owners who use a third-party economy plugin and **don't want to write Java code** — set `EconomyProvider` to `"generic"` and fill in the `GenericEconomy` section:

```json
"General": {
  "EconomyProvider": "generic"
},
"GenericEconomy": {
  "ClassName": "com.example.economy.EconomyAPI",
  "InstanceMethod": "",
  "DepositMethod": "deposit",
  "BalanceMethod": "getBalance",
  "DepositHasReason": false
}
```

<details>
<summary>Field reference</summary>

| Field | Description |
|:------|:------------|
| `ClassName` | Full class name of the economy plugin's API |
| `InstanceMethod` | Static method returning the API instance (leave `""` for static methods) |
| `DepositMethod` | Method name for adding money (`UUID` + `double`) |
| `BalanceMethod` | Method name for getting balance (`UUID` → `double`) |
| `DepositHasReason` | `true` if deposit accepts a third `String` parameter |

Auto-detected signatures: `(UUID, double)`, `(UUID, double, String)`, `(String, double)`, `(UUID, int)`
</details>

### General Settings

| Setting | Default | Description |
|:--------|:--------|:------------|
| `RoundToWholeNumbers` | `false` | Round all income to whole numbers (e.g. 6.57 → 7) |
| `EconomyProvider` | `"ecotale"` | Economy provider key |
| `NotifyOnReward` | `true` | Show chat messages on income |
| `DebugMode` | `false` | Enable verbose debug logging |

### Anti-Farm & Exploit Protection

| Setting | Default | Description |
|:--------|:--------|:------------|
| `MaxRewardsPerMinute` | 60 | Hard cap on rewards per minute |
| `SameBlockCooldownMs` | 500 | Cooldown between same-position rewards |
| `DenyPlayerPlacedBlocks` | `true` | Deny income for blocks placed by players (exploit protection) |
| `PlacedBlockExpireMinutes` | 30 | How long placed-block positions are remembered |
| `AntiFarm.WindowSeconds` | 120 | Time window for diminishing returns |
| `AntiFarm.ThresholdKills` | 25 | Kills before penalty triggers |
| `AntiFarm.PenaltyMultiplier` | 0.10 | Reward multiplier after threshold |

### Custom Blocks

Add rewards for non-standard blocks (modded ores, custom trees, etc.):

```json
"CustomBlocks": {
  "Enabled": true,
  "Blocks": {
    "my_custom_ore":  { "Min": 2.0, "Max": 5.0, "Category": "ore" },
    "my_custom_tree": { "Min": 0.5, "Max": 1.5, "Category": "wood" },
    "my_custom_crop": { "Min": 0.3, "Max": 0.8, "Category": "crop" }
  }
}
```

## 🔌 Economy API

EcoTaleIncome provides a public Java API for integrating custom economy plugins.

### Quick Integration

**1. Add dependency** (compile-only):

```gradle
dependencies {
    compileOnly files('libs/EcoTaleIncome-1.2.5.jar')
}
```

**2. Implement `EconomyProvider`:**

```java
import com.crystalrealm.ecotaleincome.economy.EconomyProvider;
import java.util.UUID;

public class MyEconomyProvider implements EconomyProvider {

    @Override
    public String getName() { return "My Economy"; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public boolean deposit(UUID playerUuid, double amount, String reason) {
        return MyEconomy.addBalance(playerUuid, amount);
    }

    @Override
    public double getBalance(UUID playerUuid) {
        return MyEconomy.getBalance(playerUuid);
    }
}
```

**3. Register in your plugin:**

```java
import com.crystalrealm.ecotaleincome.api.EcoTaleIncomeAPI;

@Override
protected void start() {
    EcoTaleIncomeAPI.registerEconomyProvider("myeconomy", new MyEconomyProvider());
    EcoTaleIncomeAPI.activateProvider("myeconomy"); // optional
}
```

**4. Users set in config:** `"EconomyProvider": "myeconomy"`

### API Reference

| Method | Description |
|:-------|:------------|
| `registerEconomyProvider(key, provider)` | Register a custom economy provider |
| `activateProvider(key)` | Switch to a specific provider at runtime |
| `getActiveProviderName()` | Get the currently active provider name |
| `isEconomyAvailable()` | Check if economy is working |

## 🏗️ Building from Source

```bash
git clone https://github.com/CrystalRealm/EcoTaleIncome.git
cd EcoTaleIncome
./gradlew jar
```

Output: `build/libs/EcoTaleIncome-1.2.5.jar`

> [!NOTE]
> The project uses compile-only stubs for Hytale Server API, Ecotale, and RPG Leveling (located in `src/stubs/java/`). No external JAR downloads needed.

## 📁 Project Structure

```
EcoTaleIncome/
├── manifest.json                              # Hytale mod descriptor
├── build.gradle                               # Gradle build config
│
├── src/main/java/com/crystalrealm/ecotaleincome/
│   ├── EcoTaleIncomePlugin.java              # Plugin entry point
│   ├── api/
│   │   └── EcoTaleIncomeAPI.java             # Public API
│   ├── config/
│   │   ├── IncomeConfig.java                 # Config POJO
│   │   └── ConfigManager.java               # Load / hot-reload
│   ├── economy/
│   │   ├── EconomyBridge.java                # Provider manager
│   │   ├── EconomyProvider.java              # Provider interface
│   │   ├── EcotaleProvider.java              # Built-in Ecotale
│   │   └── GenericEconomyProvider.java       # Reflection adapter
│   ├── rpg/
│   │   └── RPGLevelingBridge.java            # RPG Leveling integration
│   ├── reward/
│   │   ├── RewardCalculator.java             # Reward computation
│   │   ├── MultiplierResolver.java           # VIP multipliers
│   │   └── RewardResult.java                 # Result DTO
│   ├── listeners/
│   │   ├── MobKillListener.java              # Kill events (ECS)
│   │   ├── MiningListener.java               # Ore break events
│   │   ├── WoodcuttingListener.java          # Tree break events
│   │   ├── FarmingListener.java              # Crop events (Use + Break)
│   │   └── BlockPlaceListener.java           # Place tracking (exploit guard)
│   ├── protection/
│   │   ├── AntiFarmManager.java              # Diminishing returns
│   │   ├── CooldownTracker.java              # Rate limiting
│   │   └── PlacedBlockTracker.java           # Placed-block position memory
│   ├── commands/
│   │   └── IncomeCommandCollection.java      # /income commands
│   ├── lang/
│   │   └── LangManager.java                  # RU/EN localization
│   └── util/
│       ├── MessageUtil.java                   # MiniMessage formatting
│       └── PluginLogger.java                  # SLF4J-style logger
│
├── src/main/resources/
│   ├── default-config.json                    # Default configuration
│   └── lang/
│       ├── en.json                            # English messages
│       └── ru.json                            # Russian messages
│
└── src/stubs/java/                            # Compile-only API stubs
    ├── com/hypixel/hytale/                    # Hytale Server API
    ├── com/ecotale/api/                       # Ecotale API
    └── org/zuxaw/plugin/api/                  # RPG Leveling API
```

## 🤝 Contributing

Contributions are welcome! Feel free to:

1. **Fork** the repository
2. Create a **feature branch** (`git checkout -b feature/amazing-feature`)
3. **Commit** your changes (`git commit -m 'Add amazing feature'`)
4. **Push** to the branch (`git push origin feature/amazing-feature`)
5. Open a **Pull Request**

### Ideas for Contributions

- 🎣 New income sources (fishing, trading, crafting)
- 💱 Additional economy provider integrations
- 🌐 More language translations
- ⚡ Performance optimizations

## � License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

## 📝 Changelog

### v1.2.0 — 2026-02-14
- **[CRITICAL] Fixed false block name matching** — structural blocks (stairs, slabs, fences, etc.) no longer falsely match resource names (e.g. "corner stair" ≠ "Corn", "oak log stairs" ≠ "Oak").
- **New: Player-placed block protection** — blocks placed by players are tracked and won't grant income when broken. Prevents the place-and-break money exploit. Configurable via `Protection.DenyPlayerPlacedBlocks` and `Protection.PlacedBlockExpireMinutes`.
- **New: Whole-number rounding** — `General.RoundToWholeNumbers` option rounds all income to integers.
- All block name matching now uses word-boundary segmentation instead of substring checks.
- Centralized rounding logic via `RewardCalculator.roundFinal()` / `getMinAmount()`.

### v1.1.1 — 2026-02-13
- **Fix:** Startup crash `NoClassDefFoundError: GameMode`. Replaced with string-based API.
- Added LuckPerms support (optional). Three-tier permission check.

### v1.1.0 — 2026-02-10
- **New:** Language subcommands — `/income langen`, `/income langru` (replaces `/income lang <code>`)
- **Fix:** `NoSuchFieldError: ArgTypes.STRING` crash that prevented plugin from loading
- `/income lang` now shows usage hint with new syntax

### v1.0.1
- Initial release

---

<div align="center">

**Developed by [CrystalRealm](https://hytale-server.pro-gamedev.ru)** for the Crystal Realm Hytale server

`hytale.pro-gamedev.ru`

[![Website](https://img.shields.io/badge/Website-hytale--server.pro--gamedev.ru-0ea5e9?style=flat-square)](https://hytale-server.pro-gamedev.ru)
[![CurseForge](https://img.shields.io/badge/CurseForge-Projects-f16436?style=flat-square&logo=curseforge)](https://www.curseforge.com/members/thefokysnik/projects)

</div>
