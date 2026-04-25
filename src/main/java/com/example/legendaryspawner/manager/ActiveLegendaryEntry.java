package com.example.legendaryspawner.manager;

import java.util.UUID;

/**
 * Representa un legendario activo en el servidor.
 */
public class ActiveLegendaryEntry {

    public final UUID   entityUuid;
    public final String speciesName;
    public final double spawnX, spawnY, spawnZ;
    public final String biomeName;
    public final long   spawnedAt;          // System.currentTimeMillis()
    public       long   scheduledDespawnAt; // millis

    public ActiveLegendaryEntry(UUID entityUuid, String speciesName,
                                 double x, double y, double z,
                                 String biomeName, long spawnedAt) {
        this.entityUuid  = entityUuid;
        this.speciesName = speciesName;
        this.spawnX      = x;
        this.spawnY      = y;
        this.spawnZ      = z;
        this.biomeName   = biomeName;
        this.spawnedAt   = spawnedAt;
    }
}
