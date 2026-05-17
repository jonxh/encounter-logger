package com.osrssim.encounterlogger;

/**
 * Snapshot of a tracked NPC's previous state, used to detect state changes
 * so the recorder can emit npc_changed events only when something actually changes.
 *
 * All fields are raw cache/API values — no interpretation.
 */
public class PreviousNpcState
{
    public int  id;
    public int  animation;
    public int  poseAnimation;
    public int  graphic;
    public int  healthRatio;
    public int  healthScale;
    public int  orientation;
    public String overheadText;
    public String interactingName;
    public String overheadIcon;  // HeadIcon enum name, null if no prayer

    public PreviousNpcState(
        int id, int animation, int poseAnimation, int graphic,
        int healthRatio, int healthScale, int orientation,
        String overheadText, String interactingName, String overheadIcon
    )
    {
        this.id              = id;
        this.animation       = animation;
        this.poseAnimation   = poseAnimation;
        this.graphic         = graphic;
        this.healthRatio     = healthRatio;
        this.healthScale     = healthScale;
        this.orientation     = orientation;
        this.overheadText    = overheadText;
        this.interactingName = interactingName;
        this.overheadIcon    = overheadIcon;
    }
}
