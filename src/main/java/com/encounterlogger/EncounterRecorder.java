package com.osrssim.encounterlogger;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
// InventoryID constants: 0 = inv, 94 = equipment — kept as raw ints for cross-version safety

import java.time.Instant;
import java.util.*;

/**
 * EncounterRecorder
 *
 * Central class that converts RuneLite API data into raw JSONL event maps
 * and forwards them to EncounterLogWriter.
 *
 * Rules:
 * - No boss-specific logic.
 * - No interpretation of mechanics.
 * - No deduplication by ID alone (always include index + location).
 * - All values are preserved as raw numbers/strings.
 */
@Slf4j
public class EncounterRecorder
{
    private static final int FLUSH_EVERY_TICKS = 20;

    private final Client              client;
    private final EncounterLogWriter  writer;
    private final EncounterLoggerConfig config;

    // Session state
    private String  sessionId;
    private int     localTick;
    private boolean recording;

    // NPC state tracking: index → previous state
    private final Map<Integer, PreviousNpcState> prevNpcStates = new HashMap<>();

    // Varbit/varp watching
    private int[] watchedVarbitIds = new int[0];
    private int[] watchedVarpIds   = new int[0];
    private final Map<Integer, Integer> prevVarbitValues = new HashMap<>();
    private final Map<Integer, Integer> prevVarpValues   = new HashMap<>();

    // Scene load tracking — used to suppress the initial area-load object flood.
    // Resets whenever a LOADING game state is observed.
    private int sceneLoadTick = -9999;

    // Blocked object IDs — scene objects whose IDs appear in this set are never logged.
    // Populated from config at recording start.
    private final Set<Integer> blockedObjectIds = new HashSet<>();

    // Player attack tracking — detect when animation changes to emit player_attack events.
    private int prevPlayerAnim   = -1;
    private int prevWeaponId     = -1;  // equipment slot 3 (weapon)
    private int prevSpecEnergy   = -1;  // special attack energy (VarpID 300, ×10)
    private int prevPrayerPoints = -1;  // prayer points last tick

    public EncounterRecorder(Client client, EncounterLogWriter writer, EncounterLoggerConfig config)
    {
        this.client  = client;
        this.writer  = writer;
        this.config  = config;
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────

    public void startRecording()
    {
        if (recording) return;

        sessionId  = "session_" + Instant.now().toEpochMilli();
        localTick  = 0;
        recording  = true;
        prevNpcStates.clear();
        prevVarbitValues.clear();
        prevVarpValues.clear();
        prevPlayerAnim   = -1;
        prevWeaponId     = -1;
        prevSpecEnergy   = -1;
        prevPrayerPoints = -1;

        parseWatchedIds();
        parseBlockedObjectIds();

        int world = client.getWorld();
        writer.open(config.recordingLabel(), world, config.logToFile());

        Map<String, Object> event = EncounterJson.base("recording_start", sessionId, localTick);
        event.put("wallClock",      Instant.now().toString());
        event.put("world",          world);
        event.put("recordingLabel", config.recordingLabel());
        event.put("radius",         config.recordingRadius());
        writer.writeLine(event);

        log.info("[EncounterLogger] Recording started. Session: {}", sessionId);
    }

    public void stopRecording()
    {
        if (!recording) return;
        recording = false;

        Map<String, Object> event = EncounterJson.base("recording_stop", sessionId, localTick);
        event.put("wallClock",  Instant.now().toString());
        event.put("totalTicks", localTick);
        writer.writeLine(event);
        writer.close();

        prevNpcStates.clear();
        log.info("[EncounterLogger] Recording stopped after {} ticks.", localTick);
    }

    public boolean isRecording() { return recording; }

    // ── Game tick ─────────────────────────────────────────────────────────────

    public void onGameTick()
    {
        localTick++;

        if (config.logVarbits())
        {
            checkWatchedVarbits();
        }

        if (config.logTickSnapshots())
        {
            emitTickSnapshot();
        }

        if (config.logProjectiles())
        {
            emitProjectiles();
        }

        if (config.logGraphicsObjects())
        {
            emitGraphicsObjects();
        }

        // Always track player attacks regardless of tick-snapshot config.
        if (config.logPlayerState())
        {
            checkPlayerAttack();
        }

        if (localTick % FLUSH_EVERY_TICKS == 0)
        {
            writer.flush();
        }
    }

    // ── Player attack detection ───────────────────────────────────────────────

    /**
     * Emits a player_attack event whenever the player’s animation changes to
     * a non-idle value.  Includes the weapon in slot 3 of the equipment
     * container and the interacting NPC (index + id + name).
     *
     * This is the primary way to answer: “what weapon did the player attack
     * with, and what were they attacking, at which tick?”
     */
    private void checkPlayerAttack()
    {
        Player player = client.getLocalPlayer();
        if (player == null) return;

        int anim = player.getAnimation();

        // Only emit when animation changes to something non-idle.
        if (anim != -1 && anim != prevPlayerAnim)
        {
            int weaponId = getEquippedWeaponId();

            Map<String, Object> event = EncounterJson.base("player_attack", sessionId, localTick);
            event.put("animation", anim);
            event.put("weaponId",  weaponId);

            // Weapon changed since last recorded attack?
            if (weaponId != prevWeaponId && prevWeaponId != -1)
            {
                event.put("weaponChanged", true);
                event.put("prevWeaponId", prevWeaponId);
            }

            // Interacting target
            Actor target = player.getInteracting();
            if (target instanceof NPC)
            {
                NPC npcTarget = (NPC) target;
                event.put("targetIndex", npcTarget.getIndex());
                event.put("targetId",    npcTarget.getId());
                event.put("targetName",  npcTarget.getName());
            }
            else if (target != null)
            {
                event.put("targetName", target.getName());
            }

            writer.writeLine(event);
            prevWeaponId = weaponId;
        }
        else if (anim == -1)
        {
            // Animation reset — update weapon in case they switched without attacking yet.
            prevWeaponId = getEquippedWeaponId();
        }

        prevPlayerAnim = anim;

        // ── Spec detection ────────────────────────────────────────────────────
        // VarpID 300 holds spec energy × 10 (1000 = full bar, 0 = empty).
        int specNow = client.getVarpValue(300);
        if (prevSpecEnergy >= 0 && specNow < prevSpecEnergy)
        {
            int weaponId    = getEquippedWeaponId();
            int specUsed    = prevSpecEnergy - specNow;     // e.g. 250 = 25%
            Map<String, Object> specEvent = EncounterJson.base("player_spec", sessionId, localTick);
            specEvent.put("weaponId",       weaponId);
            specEvent.put("specBefore",     prevSpecEnergy); // ×10 (1000 = full)
            specEvent.put("specAfter",      specNow);
            specEvent.put("specUsedTenths", specUsed);       // e.g. 250 = 25%
            // Interacting target
            Player pl = client.getLocalPlayer();
            if (pl != null)
            {
                Actor tgt = pl.getInteracting();
                if (tgt instanceof NPC)
                {
                    specEvent.put("targetName",  tgt.getName());
                    specEvent.put("targetIndex", ((NPC) tgt).getIndex());
                }
            }
            writer.writeLine(specEvent);
        }
        prevSpecEnergy = specNow;
    }

    /** Returns the item ID in equipment slot 3 (weapon), or -1 if empty. */
    private int getEquippedWeaponId()
    {
        ItemContainer eq = client.getItemContainer(94); // InventoryID.EQUIPMENT
        if (eq == null) return -1;
        Item weapon = eq.getItem(3); // slot 3 = weapon
        return (weapon != null) ? weapon.getId() : -1;
    }

    // ── Tick snapshot ─────────────────────────────────────────────────────────

    private void emitTickSnapshot()
    {
        Player player = client.getLocalPlayer();
        if (player == null) return;

        Map<String, Object> event = EncounterJson.base("tick", sessionId, localTick);
        event.put("gameCycle",   client.getGameCycle());
        event.put("world",       client.getWorld());
        event.put("isInInstance", client.isInInstancedRegion());

        LocalPoint lp = player.getLocalLocation();
        if (lp != null)
        {
            WorldPoint canonical = instanceAwareWorldPoint(lp);
            event.put("playerWorld", EncounterJson.worldPoint(canonical.getX(), canonical.getY(), canonical.getPlane()));
            event.put("playerScene", EncounterJson.localPoint(lp.getSceneX(), lp.getSceneY(), canonical.getPlane()));
        }

        if (config.logPlayerState())
        {
            event.put("player", buildPlayerSnapshot(player));
        }

        // Use canonical player location for radius filtering
        WorldPoint playerCanonical = lp != null ? instanceAwareWorldPoint(lp) : player.getWorldLocation();
        List<Map<String, Object>> npcList = buildNearbyNpcList(playerCanonical);
        event.put("nearbyNpcs", npcList);
        event.put("nearbyNpcCount", npcList.size());

        emitNpcStateChanges(npcList);
        writer.writeLine(event);
    }

    // ── Player snapshot ───────────────────────────────────────────────────────

    private Map<String, Object> buildPlayerSnapshot(Player player)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("animation",      player.getAnimation());
        m.put("poseAnimation",  player.getPoseAnimation());
        m.put("graphic",        player.getGraphic());
        m.put("orientation",    player.getOrientation());
        m.put("overheadText",   player.getOverheadText());
        m.put("healthRatio",    player.getHealthRatio());
        m.put("healthScale",    player.getHealthScale());
        m.put("weaponId",       getEquippedWeaponId()); // always included; cheap read

        Actor target = player.getInteracting();
        if (target instanceof NPC)
        {
            NPC npcTarget = (NPC) target;
            m.put("interactingName",  npcTarget.getName());
            m.put("interactingIndex", npcTarget.getIndex());
            m.put("interactingId",    npcTarget.getId());
        }
        else if (target != null)
        {
            m.put("interactingName",  target.getName());
            m.put("interactingIndex", -1);
            m.put("interactingId",    -1);
        }

        // Client stats
        m.put("hp",       client.getBoostedSkillLevel(Skill.HITPOINTS));
        m.put("hpBase",   client.getRealSkillLevel(Skill.HITPOINTS));
        m.put("prayer",   client.getBoostedSkillLevel(Skill.PRAYER));
        m.put("runEnergy",client.getEnergy());

        // Attack style (VarpID 43: 0=accurate,1=aggressive,2=controlled,3=defensive)
        m.put("attackStyle", client.getVarpValue(43));

        // Special attack energy (VarpID 300 = spec bar × 10)
        m.put("specEnergy", client.getVarpValue(300));

        // Skill boosts (boosted − real = bonus/drain)
        Map<String, Integer> boosts = new LinkedHashMap<>();
        boosts.put("attack",   client.getBoostedSkillLevel(Skill.ATTACK)   - client.getRealSkillLevel(Skill.ATTACK));
        boosts.put("strength", client.getBoostedSkillLevel(Skill.STRENGTH) - client.getRealSkillLevel(Skill.STRENGTH));
        boosts.put("defence",  client.getBoostedSkillLevel(Skill.DEFENCE)  - client.getRealSkillLevel(Skill.DEFENCE));
        boosts.put("ranged",   client.getBoostedSkillLevel(Skill.RANGED)   - client.getRealSkillLevel(Skill.RANGED));
        boosts.put("magic",    client.getBoostedSkillLevel(Skill.MAGIC)    - client.getRealSkillLevel(Skill.MAGIC));
        m.put("boosts", boosts);

        // Prayer drain detection — emit separate event if prayer drained > 2 points this tick
        int prayerNow = client.getBoostedSkillLevel(Skill.PRAYER);
        if (prevPrayerPoints >= 0 && (prevPrayerPoints - prayerNow) > 2)
        {
            Map<String, Object> drainEvent = EncounterJson.base("prayer_drain", sessionId, localTick);
            drainEvent.put("prayerBefore", prevPrayerPoints);
            drainEvent.put("prayerAfter",  prayerNow);
            drainEvent.put("drained",      prevPrayerPoints - prayerNow);
            writer.writeLine(drainEvent);
        }
        prevPrayerPoints = prayerNow;

        // Active prayers
        List<String> activePrayers = new ArrayList<>();
        for (Prayer p : Prayer.values())
        {
            if (client.isPrayerActive(p))
            {
                activePrayers.add(p.name());
            }
        }
        m.put("activePrayers", activePrayers);

        if (config.logEquipment())
        {
            m.put("equipment", buildItemContainerSnapshot(94)); // InventoryID.EQUIPMENT = 94
        }

        if (config.logInventory())
        {
            m.put("inventory", buildItemContainerSnapshot(0)); // InventoryID.INV = 0
        }

        return m;
    }

    private List<Map<String, Object>> buildItemContainerSnapshot(int containerId)
    {
        ItemContainer container = client.getItemContainer(containerId);
        List<Map<String, Object>> items = new ArrayList<>();
        if (container == null) return items;

        Item[] containerItems = container.getItems();
        for (int slot = 0; slot < containerItems.length; slot++)
        {
            Item item = containerItems[slot];
            if (item == null || item.getId() == -1) continue;
            items.add(EncounterJson.itemSnapshot(slot, item.getId(), null, item.getQuantity()));
        }
        return items;
    }

    // ── NPC snapshots ─────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildNearbyNpcList(WorldPoint playerWp)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        int radius = config.recordingRadius();

        for (NPC npc : client.getNpcs())
        {
            if (npc == null) continue;
            WorldPoint npcWp = npc.getWorldLocation();
            if (npcWp == null) continue;
            if (!isWithinRadius(playerWp, npcWp, radius)) continue;
            list.add(buildNpcSnapshot(npc));
        }
        return list;
    }

    private Map<String, Object> buildNpcSnapshot(NPC npc)
    {
        LocalPoint lp      = npc.getLocalLocation();
        Actor target       = npc.getInteracting();
        NPCComposition comp = npc.getComposition();

        int combatLevel = (comp != null) ? comp.getCombatLevel() : -1;
        int size        = (comp != null) ? comp.getSize() : 1;

        // Use instance-aware coordinate so instanced arenas (e.g. Doom) log
        // canonical OSRS coords (1311,9559) not instance-space (11135,10490).
        WorldPoint canonical = (lp != null) ? instanceAwareWorldPoint(lp) : npc.getWorldLocation();

        // Overhead prayer icon — the old NPC.getOverheadIcon() API was removed.
        // getOverheadArchiveIds() returns a sparse int[] where -1 = unused slot.
        // We log the first active archive ID as a raw string for the parser to display.
        String overheadIconStr = null;
        int[] archiveIds = npc.getOverheadArchiveIds();
        if (archiveIds != null)
        {
            for (int aid : archiveIds)
            {
                if (aid != -1)
                {
                    overheadIconStr = String.valueOf(aid);
                    break;
                }
            }
        }

        return EncounterJson.npcSnapshot(
            npc.getIndex(),
            npc.getId(),
            npc.getName(),
            canonical.getX(), canonical.getY(), canonical.getPlane(),
            lp != null ? lp.getSceneX() : -1,
            lp != null ? lp.getSceneY() : -1,
            size, combatLevel,
            npc.getAnimation(),
            npc.getPoseAnimation(),
            npc.getGraphic(),
            npc.getOrientation(),
            target != null ? target.getName() : null,
            target instanceof NPC ? ((NPC) target).getIndex() : -1,
            npc.getHealthRatio(),
            npc.getHealthScale(),
            npc.getOverheadText(),
            npc.isDead(),
            overheadIconStr
        );
    }

    // ── NPC change detection ──────────────────────────────────────────────────

    private void emitNpcStateChanges(List<Map<String, Object>> currentNpcs)
    {
        if (!config.logNpcEvents()) return;

        Set<Integer> seenIndices = new HashSet<>();

        for (Map<String, Object> snap : currentNpcs)
        {
            int index = (int) snap.get("index");
            seenIndices.add(index);
            PreviousNpcState prev = prevNpcStates.get(index);

            int animation     = (int) snap.get("animation");
            int poseAnimation = (int) snap.get("poseAnimation");
            int graphic       = (int) snap.get("graphic");
            int healthRatio   = (int) snap.get("healthRatio");
            int healthScale   = (int) snap.get("healthScale");
            int orientation   = (int) snap.get("orientation");
            String overhead   = (String) snap.get("overheadText");
            String interacting= (String) snap.get("interactingName");
            String overheadIcon = (String) snap.get("overheadIcon");

            if (prev == null)
            {
                // NPC entered radius this tick — not necessarily a game spawn
                prevNpcStates.put(index, new PreviousNpcState(
                    (int) snap.get("id"), animation, poseAnimation, graphic,
                    healthRatio, healthScale, orientation, overhead, interacting, overheadIcon
                ));
                continue;
            }

            // Collect changed fields
            Map<String, Object> changes = new LinkedHashMap<>();
            if (prev.animation     != animation)     { changes.put("animation",     new int[]{prev.animation, animation}); prev.animation = animation; }
            if (prev.poseAnimation != poseAnimation) { changes.put("poseAnimation", new int[]{prev.poseAnimation, poseAnimation}); prev.poseAnimation = poseAnimation; }
            if (prev.graphic       != graphic)       { changes.put("graphic",       new int[]{prev.graphic, graphic}); prev.graphic = graphic; }
            if (prev.healthRatio   != healthRatio)   { changes.put("healthRatio",   new int[]{prev.healthRatio, healthRatio}); prev.healthRatio = healthRatio; }
            if (prev.healthScale   != healthScale)   { changes.put("healthScale",   new int[]{prev.healthScale, healthScale}); prev.healthScale = healthScale; }
            if (prev.orientation   != orientation)   { changes.put("orientation",   new int[]{prev.orientation, orientation}); prev.orientation = orientation; }
            if (!Objects.equals(prev.overheadText, overhead))      { changes.put("overheadText",    new String[]{prev.overheadText, overhead}); prev.overheadText = overhead; }
            if (!Objects.equals(prev.interactingName, interacting)) { changes.put("interactingName", new String[]{prev.interactingName, interacting}); prev.interactingName = interacting; }
            if (!Objects.equals(prev.overheadIcon, overheadIcon))   { changes.put("overheadIcon",    new String[]{prev.overheadIcon, overheadIcon}); prev.overheadIcon = overheadIcon; }

            if (!changes.isEmpty())
            {
                Map<String, Object> event = EncounterJson.base("npc_changed", sessionId, localTick);
                event.put("npcIndex",  index);
                event.put("npcId",     snap.get("id"));
                event.put("npcName",   snap.get("name"));
                event.put("worldLocation", snap.get("worldLocation"));
                event.put("changes",   changes);
                writer.writeLine(event);
            }
        }

        // Remove NPCs that left radius (not a game despawn, but they're no longer tracked)
        prevNpcStates.keySet().retainAll(seenIndices);
    }

    // ── NPC event handlers ────────────────────────────────────────────────────

    public void onNpcSpawned(NpcSpawned e)
    {
        if (!recording || !config.logNpcEvents()) return;
        NPC npc = e.getNpc();
        Map<String, Object> event = EncounterJson.base("npc_spawned", sessionId, localTick);
        event.put("npcSnapshot", buildNpcSnapshot(npc));
        writer.writeLine(event);
    }

    public void onNpcDespawned(NpcDespawned e)
    {
        if (!recording || !config.logNpcEvents()) return;
        NPC npc = e.getNpc();
        LocalPoint lp = npc.getLocalLocation();

        Map<String, Object> event = EncounterJson.base("npc_despawned", sessionId, localTick);
        event.put("npcIndex", npc.getIndex());
        event.put("npcId",    npc.getId());
        event.put("npcName",  npc.getName());
        if (lp != null)
        {
            WorldPoint canonical = instanceAwareWorldPoint(lp);
            event.put("lastWorldLocation", EncounterJson.worldPoint(canonical.getX(), canonical.getY(), canonical.getPlane()));
        }
        writer.writeLine(event);
        prevNpcStates.remove(npc.getIndex());
    }

    // ── Hitsplat handler ──────────────────────────────────────────────────────

    public void onHitsplatApplied(HitsplatApplied e)
    {
        if (!recording || !config.logHitsplats()) return;

        Actor actor = e.getActor();
        Hitsplat hs = e.getHitsplat();

        Map<String, Object> event = EncounterJson.base("hitsplat", sessionId, localTick);
        event.put("amount",      hs.getAmount());
        event.put("hitsplatType",hs.getHitsplatType());
        event.put("isMe",        hs.isMine());
        event.put("isOthers",    hs.isOthers());
        event.put("disappearsOnGameCycle", hs.getDisappearsOnGameCycle());

        if (actor instanceof NPC)
        {
            NPC npc = (NPC) actor;
            event.put("targetType",  "npc");
            event.put("targetIndex", npc.getIndex());
            event.put("targetId",    npc.getId());
            event.put("targetName",  npc.getName());
            event.put("targetHealthRatio", npc.getHealthRatio());
            event.put("targetHealthScale", npc.getHealthScale());
        }
        else if (actor instanceof Player)
        {
            event.put("targetType", "player");
            event.put("targetName", actor.getName());
            event.put("targetHealthRatio", actor.getHealthRatio());
        }

        LocalPoint lp = actor.getLocalLocation();
        if (lp != null)
        {
            WorldPoint canonical = instanceAwareWorldPoint(lp);
            event.put("targetWorldLocation", EncounterJson.worldPoint(canonical.getX(), canonical.getY(), canonical.getPlane()));
        }

        writer.writeLine(event);
    }

    // ── Sound effect handlers ─────────────────────────────────────────────────

    /** Client-wide sound (UI, prayers, eating, etc.) */
    public void onSoundEffectPlayed(SoundEffectPlayed e)
    {
        if (!recording || !config.logSounds()) return;
        Map<String, Object> event = EncounterJson.base("sound", sessionId, localTick);
        event.put("soundId", e.getSoundId());
        event.put("delay",   e.getDelay());
        event.put("source",  "client");
        writer.writeLine(event);
    }

    /** World-positioned sound — includes tile of origin. */
    public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed e)
    {
        if (!recording || !config.logSounds()) return;
        Map<String, Object> event = EncounterJson.base("sound", sessionId, localTick);
        event.put("soundId", e.getSoundId());
        event.put("delay",   e.getDelay());
        event.put("source",  "area");
        // Source entity
        Actor src = e.getSource();
        if (src instanceof NPC)
        {
            NPC npc = (NPC) src;
            event.put("sourceNpcId",    npc.getId());
            event.put("sourceNpcName",  npc.getName());
            event.put("sourceNpcIndex", npc.getIndex());
        }
        WorldPoint wp = (src != null)
            ? instanceAwareWorldPoint(src.getLocalLocation())
            : null;
        if (wp != null) event.put("sourceWorldLocation", EncounterJson.worldPoint(wp.getX(), wp.getY(), wp.getPlane()));
        writer.writeLine(event);
    }

    // ── Chat/overhead message handler ─────────────────────────────────────────

    public void onChatMessage(ChatMessage e)
    {
        if (!recording || !config.logChatMessages()) return;

        Map<String, Object> event = EncounterJson.base("chat_message", sessionId, localTick);
        event.put("messageType", e.getType().name());
        event.put("sender",      e.getName());
        event.put("message",     e.getMessage());
        writer.writeLine(event);
    }

    public void onOverheadTextChanged(OverheadTextChanged e)
    {
        if (!recording || !config.logChatMessages()) return;

        Actor actor = e.getActor();
        Map<String, Object> event = EncounterJson.base("overhead_text_changed", sessionId, localTick);
        event.put("text", e.getOverheadText());

        if (actor instanceof NPC)
        {
            NPC npc = (NPC) actor;
            event.put("actorType",  "npc");
            event.put("npcIndex",   npc.getIndex());
            event.put("npcId",      npc.getId());
            event.put("npcName",    npc.getName());
        }
        else
        {
            event.put("actorType",  "player");
            event.put("actorName",  actor.getName());
        }

        writer.writeLine(event);
    }

    // ── Projectiles ───────────────────────────────────────────────────────────

    private void emitProjectiles()
    {
        net.runelite.api.Deque<Projectile> projectiles = client.getProjectiles();
        if (projectiles == null) return;

        for (Projectile p : projectiles)
        {
            Actor target = p.getInteracting();
            int targetIndex = (target instanceof NPC) ? ((NPC) target).getIndex() : -1;

            WorldPoint targetWp = null;
            if (p.getTarget() != null)
            {
                targetWp = WorldPoint.fromLocal(client, p.getTarget());
            }

            Map<String, Object> event = EncounterJson.base("projectile", sessionId, localTick);
            event.put("projectileSnapshot", EncounterJson.projectileSnapshot(
                p.getId(),
                p.getX(), p.getY(), p.getZ(),
                p.getStartCycle(), p.getEndCycle(), p.getRemainingCycles(),
                p.getSlope(), p.getStartHeight(), p.getEndHeight(),
                targetIndex,
                targetWp != null ? targetWp.getX() : -1,
                targetWp != null ? targetWp.getY() : -1,
                -1, -1  // source coords — not directly exposed in this API version
            ));
            writer.writeLine(event);
        }
    }

    // ── Graphics objects (spotanims on tiles) ─────────────────────────────────

    private void emitGraphicsObjects()
    {
        net.runelite.api.Deque<GraphicsObject> gfxObjects = client.getGraphicsObjects();
        if (gfxObjects == null) return;

        Player player = client.getLocalPlayer();
        if (player == null) return;
        WorldPoint playerWp = player.getWorldLocation();
        int radius = config.recordingRadius();

        for (GraphicsObject go : gfxObjects)
        {
            LocalPoint lp = go.getLocation();
            if (lp == null) continue;
            WorldPoint wp = WorldPoint.fromLocal(client, lp);
            if (!isWithinRadius(playerWp, wp, radius)) continue;

            Map<String, Object> event = EncounterJson.base("graphics_object", sessionId, localTick);
            event.put("graphicsObjectSnapshot", EncounterJson.graphicsObjectSnapshot(
                go.getId(),
                wp.getX(), wp.getY(), client.getPlane(),
                lp.getSceneX(), lp.getSceneY(),
                -1, go.getStartCycle()  // GraphicsObject has no getHeight() in this API version
            ));
            writer.writeLine(event);
        }
    }

    // ── Scene object events ───────────────────────────────────────────────────

    /**
     * Called by the plugin when GameStateChanged fires.
     * Resets sceneLoadTick so spawned events fired during the initial scene
     * population (area-load flood) are suppressed for the configured grace period.
     */
    public void onSceneLoading()
    {
        sceneLoadTick = localTick;
        log.debug("[EncounterLogger] Scene loading at tick {}, grace period = {} ticks",
            localTick, config.sceneObjectGracePeriod());
    }

    /** True if we are still within the scene-load suppression window. */
    private boolean isInSceneGracePeriod()
    {
        int grace = config.sceneObjectGracePeriod();
        return grace > 0 && (localTick - sceneLoadTick) < grace;
    }

    public void onGameObjectSpawned(GameObjectSpawned e)
    {
        if (!recording || !config.logGameObjects() || isInSceneGracePeriod()) return;
        GameObject obj = e.getGameObject();
        emitSceneObject("game_object_spawned", "game_object", obj.getId(),
            obj.getWorldLocation(), obj.sizeX(), obj.sizeY(), obj.getOrientation());
    }

    public void onGameObjectDespawned(GameObjectDespawned e)
    {
        if (!recording || !config.logGameObjects()) return;
        GameObject obj = e.getGameObject();
        emitSceneObject("game_object_despawned", "game_object", obj.getId(),
            obj.getWorldLocation(), obj.sizeX(), obj.sizeY(), obj.getOrientation());
    }

    public void onGroundObjectSpawned(GroundObjectSpawned e)
    {
        if (!recording || !config.logGameObjects() || isInSceneGracePeriod()) return;
        GroundObject obj = e.getGroundObject();
        emitSceneObject("ground_object_spawned", "ground_object", obj.getId(),
            obj.getWorldLocation(), 1, 1, 0);
    }

    public void onGroundObjectDespawned(GroundObjectDespawned e)
    {
        if (!recording || !config.logGameObjects()) return;
        GroundObject obj = e.getGroundObject();
        emitSceneObject("ground_object_despawned", "ground_object", obj.getId(),
            obj.getWorldLocation(), 1, 1, 0);
    }

    public void onWallObjectSpawned(WallObjectSpawned e)
    {
        if (!recording || !config.logGameObjects() || isInSceneGracePeriod()) return;
        WallObject obj = e.getWallObject();
        emitSceneObject("wall_object_spawned", "wall_object", obj.getId(),
            obj.getWorldLocation(), 1, 1, obj.getOrientationA());
    }

    public void onWallObjectDespawned(WallObjectDespawned e)
    {
        if (!recording || !config.logGameObjects()) return;
        WallObject obj = e.getWallObject();
        emitSceneObject("wall_object_despawned", "wall_object", obj.getId(),
            obj.getWorldLocation(), 1, 1, obj.getOrientationA());
    }

    public void onDecorativeObjectSpawned(DecorativeObjectSpawned e)
    {
        if (!recording || !config.logGameObjects() || isInSceneGracePeriod()) return;
        DecorativeObject obj = e.getDecorativeObject();
        // DecorativeObject has no getOrientation() in this API version
        emitSceneObject("decorative_object_spawned", "decorative_object", obj.getId(),
            obj.getWorldLocation(), 1, 1, -1);
    }

    public void onDecorativeObjectDespawned(DecorativeObjectDespawned e)
    {
        if (!recording || !config.logGameObjects()) return;
        DecorativeObject obj = e.getDecorativeObject();
        emitSceneObject("decorative_object_despawned", "decorative_object", obj.getId(),
            obj.getWorldLocation(), 1, 1, -1);
    }

    private void emitSceneObject(String type, String category, int id,
        WorldPoint wp, int sx, int sy, int orientation)
    {
        // Silent blocklist check — IDs the user added from the parser noise report.
        if (blockedObjectIds.contains(id)) return;

        Map<String, Object> event = EncounterJson.base(type, sessionId, localTick);
        event.put("sceneObject", EncounterJson.sceneObjectSnapshot(
            category, id, null,
            wp.getX(), wp.getY(), wp.getPlane(),
            sx, sy, orientation
        ));
        writer.writeLine(event);
    }

    // ── Ground items ──────────────────────────────────────────────────────────

    public void onItemSpawned(ItemSpawned e)
    {
        if (!recording || !config.logGroundItems()) return;
        TileItem item = e.getItem();
        WorldPoint wp = e.getTile().getWorldLocation();

        Map<String, Object> event = EncounterJson.base("ground_item_spawned", sessionId, localTick);
        event.put("itemId",       item.getId());
        event.put("quantity",     item.getQuantity());
        event.put("worldLocation",EncounterJson.worldPoint(wp.getX(), wp.getY(), wp.getPlane()));
        writer.writeLine(event);
    }

    public void onItemDespawned(ItemDespawned e)
    {
        if (!recording || !config.logGroundItems()) return;
        TileItem item = e.getItem();
        WorldPoint wp = e.getTile().getWorldLocation();

        Map<String, Object> event = EncounterJson.base("ground_item_despawned", sessionId, localTick);
        event.put("itemId",       item.getId());
        event.put("quantity",     item.getQuantity());
        event.put("worldLocation",EncounterJson.worldPoint(wp.getX(), wp.getY(), wp.getPlane()));
        writer.writeLine(event);
    }

    // ── Varbit/varp watching ──────────────────────────────────────────────────

    private void parseWatchedIds()
    {
        watchedVarbitIds = parseIntCsv(config.watchedVarbitIds());
        watchedVarpIds   = parseIntCsv(config.watchedVarpIds());
        // Seed current values to avoid false change on first tick
        for (int id : watchedVarbitIds) prevVarbitValues.put(id, client.getVarbitValue(id));
        for (int id : watchedVarpIds)   prevVarpValues.put(id, client.getVarpValue(id));
    }

    private void parseBlockedObjectIds()
    {
        blockedObjectIds.clear();
        for (int id : parseIntCsv(config.blockedObjectIds()))
        {
            blockedObjectIds.add(id);
        }
        log.debug("[EncounterLogger] Blocked object IDs: {}", blockedObjectIds);
    }

    private void checkWatchedVarbits()
    {
        for (int id : watchedVarbitIds)
        {
            int current = client.getVarbitValue(id);
            int prev    = prevVarbitValues.getOrDefault(id, current);
            if (current != prev)
            {
                Map<String, Object> event = EncounterJson.base("varbit_changed", sessionId, localTick);
                event.put("varbitId", id);
                event.put("from",     prev);
                event.put("to",       current);
                writer.writeLine(event);
                prevVarbitValues.put(id, current);
            }
        }
        for (int id : watchedVarpIds)
        {
            int current = client.getVarpValue(id);
            int prev    = prevVarpValues.getOrDefault(id, current);
            if (current != prev)
            {
                Map<String, Object> event = EncounterJson.base("varp_changed", sessionId, localTick);
                event.put("varpId", id);
                event.put("from",   prev);
                event.put("to",     current);
                writer.writeLine(event);
                prevVarpValues.put(id, current);
            }
        }
    }

    // ── Varbit discovery mode ────────────────────────────────────────────────

    /**
     * Discovery mode: fires for EVERY varbit or varp change.
     * Emits a 'varbit_discovery' event (not 'varbit_changed') so the
     * parser/UI can aggregate these into a summary table without polluting
     * the per-tick timeline view.
     */
    public void onVarbitChanged(VarbitChanged e)
    {
        if (!recording || !config.logAllVarbitChanges()) return;

        int varbitId = e.getVarbitId();
        int varpId = e.getVarpId();
        int value = e.getValue();

        if (varbitId != -1)
        {
            // Skip varbits already on the explicit watchlist
            for (int watched : watchedVarbitIds)
            {
                if (watched == varbitId) return;
            }

            Map<String, Object> event = EncounterJson.base("varbit_discovery", sessionId, localTick);
            event.put("kind",  "varbit");
            event.put("id",    varbitId);
            event.put("to",    value);
            writer.writeLine(event);
        }
        else
        {
            // Skip varps already on the explicit watchlist
            for (int watched : watchedVarpIds)
            {
                if (watched == varpId) return;
            }

            Map<String, Object> event = EncounterJson.base("varbit_discovery", sessionId, localTick);
            event.put("kind",  "varp");
            event.put("id",    varpId);
            event.put("to",    value);
            writer.writeLine(event);
        }
    }

    // ── Instance-aware coordinate helper ─────────────────────────────────────

    /**
     * Convert a LocalPoint (scene-space) to a canonical WorldPoint.
     *
     * In instanced regions (boss arenas, raids, etc.) getWorldLocation() on
     * actors returns the instance-space coordinate (e.g. 11135,10490) rather
     * than the real OSRS map coordinate (e.g. 1311,9559).
     *
     * WorldPoint.fromLocalInstance() de-references the instance mapping so
     * the returned coordinate matches the OSRS Wiki and your simulator data.
     *
     * Outside instances this is identical to WorldPoint.fromLocal().
     */
    private WorldPoint instanceAwareWorldPoint(LocalPoint lp)
    {
        if (client.isInInstancedRegion())
        {
            return WorldPoint.fromLocalInstance(client, lp);
        }
        return WorldPoint.fromLocal(client, lp);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private boolean isWithinRadius(WorldPoint origin, WorldPoint target, int radius)
    {
        return Math.abs(origin.getX() - target.getX()) <= radius
            && Math.abs(origin.getY() - target.getY()) <= radius
            && origin.getPlane() == target.getPlane();
    }

    private static int[] parseIntCsv(String csv)
    {
        if (csv == null || csv.isBlank()) return new int[0];
        String[] parts = csv.split("[,\\s]+");
        List<Integer> ids = new ArrayList<>();
        for (String p : parts)
        {
            p = p.trim();
            if (!p.isEmpty())
            {
                try { ids.add(Integer.parseInt(p)); }
                catch (NumberFormatException ignored) {}
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }
}
