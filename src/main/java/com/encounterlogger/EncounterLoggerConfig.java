package com.encounterlogger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("encounter-logger")
public interface EncounterLoggerConfig extends Config {
    // ── Recording control ────────────────────────────────────────────────────

    @ConfigItem(keyName = "collectData", name = "Collect data", description = "Start/stop recording encounter data as JSONL.", position = 0)
    default boolean collectData() {
        return false;
    }

    @ConfigItem(keyName = "recordingLabel", name = "Recording label", description = "Label included in the output filename (e.g. 'doom_delve4').", position = 1)
    default String recordingLabel() {
        return "encounter";
    }

    @ConfigItem(keyName = "recordingRadius", name = "Recording radius (tiles)", description = "Only include NPCs/objects within this tile radius of the player.", position = 2)
    default int recordingRadius() {
        return 64;
    }

    // ── What to log ──────────────────────────────────────────────────────────

    @ConfigItem(keyName = "logTickSnapshots", name = "Log tick snapshots", description = "Emit a full snapshot of all nearby NPCs and player state each game tick.", position = 10)
    default boolean logTickSnapshots() {
        return true;
    }

    @ConfigItem(keyName = "logNpcEvents", name = "Log NPC events", description = "Log NPC spawn, despawn, animation, graphic, and HP changes.", position = 11)
    default boolean logNpcEvents() {
        return true;
    }

    @ConfigItem(keyName = "logProjectiles", name = "Log projectiles", description = "Log all projectiles each tick (id, position, cycles, target).", position = 12)
    default boolean logProjectiles() {
        return true;
    }

    @ConfigItem(keyName = "logGraphicsObjects", name = "Log graphics objects (spotanims)", description = "Log GraphicsObject spawn events (spotanims on tiles).", position = 13)
    default boolean logGraphicsObjects() {
        return true;
    }

    @ConfigItem(keyName = "logGameObjects", name = "Log game/scene objects", description = "Log scene object spawn/despawn (game objects, walls, ground, decorative).", position = 14)
    default boolean logGameObjects() {
        return false;
    }

    @ConfigItem(keyName = "sceneObjectGracePeriod", name = "Scene load grace period (ticks)", description = "Suppress scene object spawn events for this many ticks after a scene change. "
            +
            "Prevents the initial area-load flood (e.g. 9000+ wall objects on entry). " +
            "Set to 0 to log everything. Has no effect when Log game/scene objects is OFF.", position = 15)
    default int sceneObjectGracePeriod() {
        return 10;
    }

    @ConfigItem(keyName = "blockedObjectIds", name = "Blocked object IDs", description = "Comma-separated game/wall/ground/decorative object IDs to never log. "
            +
            "Use the parser Noise Report to identify high-frequency static objects " +
            "(e.g. arena floor tiles, permanent walls) and paste their IDs here. " +
            "Example: 51462, 85, 16526", position = 16)
    default String blockedObjectIds() {
        return "";
    }

    @ConfigItem(keyName = "logGroundItems", name = "Log ground items", description = "Log ground item spawn/despawn events.", position = 17)
    default boolean logGroundItems() {
        return false;
    }

    @ConfigItem(keyName = "logHitsplats", name = "Log hitsplats", description = "Log HitsplatApplied events on NPCs and players.", position = 16)
    default boolean logHitsplats() {
        return true;
    }

    @ConfigItem(keyName = "logMenuActions", name = "Log menu actions (red clicks)", description = "Log player menu option clicks (e.g., Attack, Walk here, Use).", position = 17)
    default boolean logMenuActions() {
        return true;
    }

    @ConfigItem(keyName = "logNpcHealthChanges", name = "Log NPC health changes", description = "Log explicit events when an NPC's health ratio changes.", position = 18)
    default boolean logNpcHealthChanges() {
        return true;
    }

    @ConfigItem(keyName = "logChatMessages", name = "Log chat messages", description = "Log chat messages (game messages, overhead text, etc.).", position = 19)
    default boolean logChatMessages() {
        return true;
    }

    @ConfigItem(keyName = "logPlayerState", name = "Log player state", description = "Include detailed player state in each tick snapshot.", position = 20)
    default boolean logPlayerState() {
        return true;
    }

    @ConfigItem(keyName = "logInventory", name = "Log inventory", description = "Include player inventory in tick snapshots (noisy).", position = 19)
    default boolean logInventory() {
        return false;
    }

    @ConfigItem(keyName = "logEquipment", name = "Log equipment", description = "Include player equipment in tick snapshots.", position = 20)
    default boolean logEquipment() {
        return true;
    }

    @ConfigItem(keyName = "logVarbits", name = "Log watched varbits/varps", description = "Log changes to the varbit/varp IDs listed below.", position = 21)
    default boolean logVarbits() {
        return false;
    }

    @ConfigItem(keyName = "watchedVarbitIds", name = "Watched varbit IDs", description = "Comma-separated varbit IDs to watch (only used when 'Log watched varbits' is on).", position = 22)
    default String watchedVarbitIds() {
        return "";
    }

    @ConfigItem(keyName = "watchedVarpIds", name = "Watched varp IDs", description = "Comma-separated varp IDs to watch (only used when 'Log watched varbits' is on).", position = 23)
    default String watchedVarpIds() {
        return "";
    }

    @ConfigItem(keyName = "logToFile", name = "Log to file", description = "Write JSONL to ~/.runelite/encounter-logger/. If off, output goes to console only.", position = 24)
    default boolean logToFile() {
        return true;
    }

    @ConfigItem(keyName = "logSounds", name = "Log sound effects", description = "Log SoundEffectPlayed and AreaSoundEffectPlayed events. "
            +
            "Sound IDs identify boss attacks by audio cue, often 1-2 ticks before visuals.", position = 25)
    default boolean logSounds() {
        return true;
    }

    @ConfigItem(keyName = "logAllVarbitChanges", name = "[Discovery] Log ALL varbit/varp changes", description = "Logs every varbit and varp change to a separate 'varbit_discovery' event. "
            +
            "VERY noisy — use for one session only to find boss-relevant IDs, " +
            "then add them to the Watched Varbit IDs list and disable this.", position = 26)
    default boolean logAllVarbitChanges() {
        return false;
    }

}
