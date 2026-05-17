package com.osrssim.encounterlogger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static factory methods for building raw JSONL event maps.
 *
 * Every method returns a LinkedHashMap so field order is preserved in output.
 * No interpretation, no deduplication, no boss-specific logic lives here.
 */
public final class EncounterJson
{
    private EncounterJson() {}

    // ── Base helpers ──────────────────────────────────────────────────────────

    public static Map<String, Object> base(String type, String sessionId, int tick)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type",      type);
        m.put("sessionId", sessionId);
        m.put("tick",      tick);
        return m;
    }

    // ── Location helpers ──────────────────────────────────────────────────────

    public static Map<String, Object> worldPoint(int x, int y, int plane)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x",     x);
        m.put("y",     y);
        m.put("plane", plane);
        return m;
    }

    public static Map<String, Object> localPoint(int x, int y, int plane)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sceneX", x);
        m.put("sceneY", y);
        m.put("plane",  plane);
        return m;
    }

    // ── NPC snapshot ──────────────────────────────────────────────────────────

    /**
     * A snapshot of a single NPC's state at one tick.
     * All values are raw — no interpretation.
     */
    public static Map<String, Object> npcSnapshot(
        int index, int id, String name,
        int worldX, int worldY, int plane,
        int sceneX, int sceneY,
        int size, int combatLevel,
        int animation, int poseAnimation, int graphic,
        int orientation,
        String interactingName, int interactingIndex,
        int healthRatio, int healthScale,
        String overheadText,
        boolean isDead,
        String overheadIcon
    )
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index",           index);
        m.put("id",              id);
        m.put("name",            name);
        m.put("worldLocation",   worldPoint(worldX, worldY, plane));
        m.put("sceneLocation",   localPoint(sceneX, sceneY, plane));
        m.put("size",            size);
        m.put("combatLevel",     combatLevel);
        m.put("animation",       animation);
        m.put("poseAnimation",   poseAnimation);
        m.put("graphic",         graphic);
        m.put("orientation",     orientation);
        m.put("interactingName", interactingName);
        m.put("interactingIndex",interactingIndex);
        m.put("healthRatio",     healthRatio);
        m.put("healthScale",     healthScale);
        m.put("overheadText",    overheadText);
        m.put("isDead",          isDead);
        m.put("overheadIcon",    overheadIcon);  // HeadIcon enum name: MELEE/RANGED/MAGIC/etc., null if no prayer
        return m;
    }

    // ── Item snapshot ─────────────────────────────────────────────────────────

    public static Map<String, Object> itemSnapshot(int slot, int id, String name, int quantity)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slot",     slot);
        m.put("id",       id);
        m.put("name",     name);
        m.put("quantity", quantity);
        return m;
    }

    // ── Projectile snapshot ───────────────────────────────────────────────────

    public static Map<String, Object> projectileSnapshot(
        int id,
        double x, double y, double z,
        int startCycle, int endCycle, int remainingCycles,
        int slope, int startHeight, int endHeight,
        int targetIndex,
        int targetX, int targetY,
        int sourceX, int sourceY
    )
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             id);
        m.put("x",              x);
        m.put("y",              y);
        m.put("z",              z);
        m.put("startCycle",     startCycle);
        m.put("endCycle",       endCycle);
        m.put("remainingCycles",remainingCycles);
        m.put("slope",          slope);
        m.put("startHeight",    startHeight);
        m.put("endHeight",      endHeight);
        m.put("targetIndex",    targetIndex);
        m.put("targetWorld",    worldPoint(targetX, targetY, -1));
        m.put("sourceWorld",    worldPoint(sourceX, sourceY, -1));
        return m;
    }

    // ── Graphics object snapshot ──────────────────────────────────────────────

    public static Map<String, Object> graphicsObjectSnapshot(
        int id,
        int worldX, int worldY, int plane,
        int sceneX, int sceneY,
        int height, int startCycle
    )
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           id);
        m.put("worldLocation",worldPoint(worldX, worldY, plane));
        m.put("sceneLocation",localPoint(sceneX, sceneY, plane));
        m.put("height",       height);
        m.put("startCycle",   startCycle);
        return m;
    }

    // ── Scene object snapshot ─────────────────────────────────────────────────

    public static Map<String, Object> sceneObjectSnapshot(
        String category, int id, String name,
        int worldX, int worldY, int plane,
        int sizeX, int sizeY, int orientation
    )
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category",     category);
        m.put("id",           id);
        m.put("name",         name);
        m.put("worldLocation",worldPoint(worldX, worldY, plane));
        m.put("sizeX",        sizeX);
        m.put("sizeY",        sizeY);
        m.put("orientation",  orientation);
        return m;
    }
}
