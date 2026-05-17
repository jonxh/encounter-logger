package com.osrssim.encounterlogger;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;

/**
 * EncounterLoggerPlugin
 *
 * A generic RuneLite external plugin that records raw OSRS encounter data
 * as JSONL for offline simulator development.
 *
 * No boss-specific logic. No gameplay assistance. No overlays.
 * Data collection only.
 */
@Slf4j
@PluginDescriptor(
    name        = "Encounter Logger",
    description = "Records raw encounter data (NPCs, projectiles, animations, hitsplats, etc.) as JSONL.",
    tags        = { "data", "recording", "npc", "boss", "simulator" }
)
public class EncounterLoggerPlugin extends Plugin
{
    @Inject private Client               client;
    @Inject private EncounterLoggerConfig config;
    @Inject private Gson                 gson;

    private EncounterLogWriter  writer;
    private EncounterRecorder   recorder;

    // Track previous collectData value to detect transitions without
    // relying solely on ConfigChanged (which can fire before new value is readable)
    private boolean prevCollectData = false;

    @Override
    protected void startUp()
    {
        writer   = new EncounterLogWriter(gson);
        recorder = new EncounterRecorder(client, writer, config);
        prevCollectData = config.collectData();
        if (prevCollectData)
        {
            recorder.startRecording();
        }
        log.info("[EncounterLogger] Plugin started.");
    }

    @Override
    protected void shutDown()
    {
        if (recorder != null && recorder.isRecording())
        {
            recorder.stopRecording();
        }
        if (writer != null)
        {
            writer.close();
        }
        recorder = null;
        writer   = null;
        log.info("[EncounterLogger] Plugin stopped.");
    }

    // ── Config transition detection ───────────────────────────────────────────

    @Subscribe
    public void onGameTick(GameTick ignored)
    {
        boolean current = config.collectData();
        if (current && !prevCollectData)
        {
            recorder.startRecording();
        }
        else if (!current && prevCollectData)
        {
            recorder.stopRecording();
        }
        prevCollectData = current;

        if (recorder != null && recorder.isRecording())
        {
            recorder.onGameTick();
        }
    }

    // ── NPC events ────────────────────────────────────────────────────────────

    @Subscribe
    public void onNpcSpawned(NpcSpawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onNpcSpawned(e);
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onNpcDespawned(e);
    }

    // ── Hitsplat ──────────────────────────────────────────────────────────────

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onHitsplatApplied(e);
    }

    // ── Chat / overhead ───────────────────────────────────────────────────────

    @Subscribe
    public void onChatMessage(ChatMessage e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onChatMessage(e);
    }

    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onOverheadTextChanged(e);
    }

    // ── Game state (scene load suppression) ───────────────────────────────────

    @Subscribe
    public void onGameStateChanged(GameStateChanged e)
    {
        // Notify recorder whenever a new scene is loading so it can reset the
        // grace period timer and suppress the initial area-load object flood.
        if (e.getGameState() == net.runelite.api.GameState.LOADING && recorder != null)
        {
            recorder.onSceneLoading();
        }
    }

    // ── Scene objects ─────────────────────────────────────────────────────────


    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onGameObjectSpawned(e);
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onGameObjectDespawned(e);
    }

    @Subscribe
    public void onGroundObjectSpawned(GroundObjectSpawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onGroundObjectSpawned(e);
    }

    @Subscribe
    public void onGroundObjectDespawned(GroundObjectDespawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onGroundObjectDespawned(e);
    }

    @Subscribe
    public void onWallObjectSpawned(WallObjectSpawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onWallObjectSpawned(e);
    }

    @Subscribe
    public void onWallObjectDespawned(WallObjectDespawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onWallObjectDespawned(e);
    }

    @Subscribe
    public void onDecorativeObjectSpawned(DecorativeObjectSpawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onDecorativeObjectSpawned(e);
    }

    @Subscribe
    public void onDecorativeObjectDespawned(DecorativeObjectDespawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onDecorativeObjectDespawned(e);
    }

    // ── Ground items ──────────────────────────────────────────────────────────

    @Subscribe
    public void onItemSpawned(ItemSpawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onItemSpawned(e);
    }

    @Subscribe
    public void onItemDespawned(ItemDespawned e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onItemDespawned(e);
    }

    // ── DI ────────────────────────────────────────────────────────────────────

    // ── Sounds ────────────────────────────────────────────────────────────────

    @Subscribe
    public void onSoundEffectPlayed(SoundEffectPlayed e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onSoundEffectPlayed(e);
    }

    @Subscribe
    public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onAreaSoundEffectPlayed(e);
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged e)
    {
        if (recorder != null && recorder.isRecording()) recorder.onVarbitChanged(e);
    }

    @Provides
    EncounterLoggerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(EncounterLoggerConfig.class);
    }
}
