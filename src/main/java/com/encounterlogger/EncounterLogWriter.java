package com.osrssim.encounterlogger;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Writes JSONL events to ~/.runelite/encounter-logger/<filename>.jsonl
 * Falls back to console (stdout) if file creation fails or file logging is disabled.
 *
 * All public methods are called only from the client thread.
 */
@Slf4j
public class EncounterLogWriter
{
    private static final File PLUGIN_DIR = new File(RuneLite.RUNELITE_DIR, "encounter-logger");
    private static final DateTimeFormatter TIMESTAMP_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss").withZone(ZoneOffset.UTC);

    private final Gson gson;

    private PrintWriter writer;
    private boolean fileMode;
    private String currentPath;

    public EncounterLogWriter(Gson gson)
    {
        this.gson = gson;
    }

    /**
     * Open a new log file (or prepare console mode).
     *
     * @param label      recording label from config
     * @param worldId    current world number (or -1 if unknown)
     * @param toFile     whether file output is enabled
     */
    public void open(String label, int worldId, boolean toFile)
    {
        close();
        fileMode = toFile;

        if (!toFile)
        {
            log.info("[EncounterLogger] File logging disabled — emitting to console only.");
            return;
        }

        PLUGIN_DIR.mkdirs();

        String timestamp = TIMESTAMP_FMT.format(Instant.now());
        String worldPart = worldId > 0 ? "_world" + worldId : "";
        String safeName  = label.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        String filename  = "encounter-log_" + safeName + "_" + timestamp + worldPart + ".jsonl";

        File file = new File(PLUGIN_DIR, filename);
        currentPath = file.getAbsolutePath();

        try
        {
            writer = new PrintWriter(new BufferedWriter(new FileWriter(file, false)));
            log.info("[EncounterLogger] Writing to: {}", currentPath);
        }
        catch (IOException e)
        {
            log.warn("[EncounterLogger] Could not open file {}: {} — falling back to console.", currentPath, e.getMessage());
            writer = null;
            fileMode = false;
        }
    }

    /** Write one JSONL line. The map is serialised with the injected Gson. */
    public void writeLine(Map<String, Object> event)
    {
        String json = gson.toJson(event);
        if (writer != null)
        {
            writer.println(json);
        }
        else
        {
            // Console fallback — use debug so it doesn't pollute RuneLite's info log
            log.debug("[EncounterLogger] {}", json);
        }
    }

    /** Flush without closing — call periodically (e.g. every N ticks). */
    public void flush()
    {
        if (writer != null)
        {
            writer.flush();
        }
    }

    /** Flush and close. Safe to call multiple times. */
    public void close()
    {
        if (writer != null)
        {
            writer.flush();
            writer.close();
            writer = null;
            log.info("[EncounterLogger] Closed log: {}", currentPath);
        }
        currentPath = null;
    }

    /** @return the absolute path of the current log file, or null if not writing to a file. */
    public String getCurrentPath()
    {
        return currentPath;
    }
}
