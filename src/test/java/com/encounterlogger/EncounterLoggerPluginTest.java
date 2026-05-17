package com.osrssim.encounterlogger;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class EncounterLoggerPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(EncounterLoggerPlugin.class);
        RuneLite.main(args);
    }
}
