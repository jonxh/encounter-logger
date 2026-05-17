# OSRS Encounter Logger (RuneLite Plugin)

A high-fidelity, lightweight data logging plugin for **RuneLite** designed to capture rich game state events during Old School RuneScape combat encounters. 

The output is emitted as high-speed `.jsonl` (JSON Lines) log files, which can be parsed by the companion **[OSRS Encounter Parser](https://github.com/your-username/osrs-encounter-parser)** web app to build offline combat simulators, analyze boss mechanics, or audit player inputs.

---

## ⚡ Features Captured

The plugin records the following events with absolute tick accuracy:

*   **NPC State Changes**: Tracks NPC positions, current animations, spotanims (graphics), orientations, interacting targets, and overhead prayer icons.
*   **Player State Snapshot**: Tracks hitpoints, stats/boosts, attack styles, active prayers, equipped items, inventory counts, and player movement.
*   **Projectiles**: Captures raw projectile details (ID, velocity, target coordinates, start/end cycle, and flight ticks).
*   **Hitsplats**: Logs all hitsplat damage amounts, types, targets, and health ratios.
*   **Graphics & Game Objects**: Logs scene VFX (spotanims) and world game objects (spawns/despawns) with a customizable blocklist.
*   **Audio Triggers**: Records game sound effects and area sounds, linking them directly to their source NPC.
*   **Player Input & Specs**: Captures weapon swaps, combat animations, and special attack energy drops.
*   **Varbit/Varp Discovery**: 
    *   **Watched Mode**: Tracks changes to a specific user-defined list of Varbit or Varp IDs (e.g. boss phases).
    *   **Discovery Mode**: An optional developer mode that logs *every single variable change* in OSRS for deep forensic analysis.

---

## 📂 Output Directory

Logs are written directly to your computer's local RuneLite folder:
```
%USERPROFILE%\.runelite\encounter-logger\
```
*Each session creates a new file, e.g. `encounter-log_boss_2026-05-17T154500_world302.jsonl`.*

---

## 🛠️ How to Build & Run

### Prerequisites
*   Java Development Kit (JDK) 11 or higher installed on your computer.

### Quick Build
1. Open a terminal/command prompt in the root of this folder.
2. Run the Gradle build command:
   ```bash
   ./gradlew build
   ```
3. Load the compiled `.jar` file directly into your RuneLite client launcher or custom development client.

---

## ⚙️ Configuration Options

Through the standard RuneLite Configuration panel, you can customize:
*   **Enable Discovery Mode**: Log all raw varbit/varp changes.
*   **Watched Varbits/Varps**: Comma-separated list of IDs to track natively.
*   **Object Blocklist**: IDs of noisy scenery objects to ignore (e.g., doors, torches).
*   **Recording Radius**: Maximum distance from the player to log events.
*   **Event Toggles**: Easily turn off high-frequency logs (like player movement or standard sounds) to save disk space.