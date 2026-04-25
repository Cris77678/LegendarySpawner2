package com.example.legendaryspawner.manager;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.example.legendaryspawner.LegendarySpawnerMod;
import com.example.legendaryspawner.config.LegendaryConfig;
import com.example.legendaryspawner.util.MessageUtil;
import com.example.legendaryspawner.webhook.DiscordWebhook;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ActiveLegendaryManager {

    private static final Path STATE_DIR  = FabricLoader.getInstance().getConfigDir().resolve("legendaryspawner");
    private static final Path STATE_FILE = STATE_DIR.resolve("active_state.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final MinecraftServer server;
    private LegendaryConfig       config;
    private final AuditLogger     auditLogger;

    private final Map<UUID, ActiveLegendaryEntry> active = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> despawnFutures = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "LegendarySpawner-Despawn");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LegendarySpawner-IO2");
        t.setDaemon(true);
        return t;
    });

    public ActiveLegendaryManager(MinecraftServer server, LegendaryConfig config, AuditLogger auditLogger) {
        this.server      = server;
        this.config      = config;
        this.auditLogger = auditLogger;
    }

    public void setConfig(LegendaryConfig newConfig) {
        this.config = newConfig;
    }

    // ── Registro de legendarios ───────────────────────────────────────────────

    public void register(PokemonEntity entity, String biomeName) {
        UUID uuid = entity.getUuid();
        String species = entity.getPokemon().getSpecies().getName();

        ActiveLegendaryEntry entry = new ActiveLegendaryEntry(
                uuid, species,
                entity.getX(), entity.getY(), entity.getZ(),
                biomeName,
                System.currentTimeMillis()
        );

        long despawnMs = config.despawnMinutes * 60_000L;
        entry.scheduledDespawnAt = System.currentTimeMillis() + despawnMs;

        active.put(uuid, entry);
        scheduleDespawn(entity, entry, despawnMs);
        saveStateAsync();

        auditLogger.logAudit(AuditLogger.EventType.SPAWN,
                String.format("Registrado %s (UUID=%s) en bioma %s [%.1f, %.1f, %.1f]",
                        species, uuid, biomeName, entry.spawnX, entry.spawnY, entry.spawnZ));
    }

    // ── Despawn ───────────────────────────────────────────────────────────────

    private void scheduleDespawn(PokemonEntity entity, ActiveLegendaryEntry entry, long delayMs) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            server.execute(() -> despawn(entity.getUuid(), "despawn automático"));
        }, delayMs, TimeUnit.MILLISECONDS);
        despawnFutures.put(entity.getUuid(), future);
    }

    public void despawn(UUID uuid, String reason) {
        ActiveLegendaryEntry entry = active.remove(uuid);
        cancelDespawnFuture(uuid);

        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(uuid);
            if (entity instanceof PokemonEntity pkEntity) {
                pkEntity.discard();
                String species = entry != null ? entry.speciesName : "desconocido";

                String msg = config.messages.despawn.replace("{pokemon}", capitalize(species));
                MessageUtil.broadcastAll(server, msg);

                auditLogger.logAudit(AuditLogger.EventType.DESPAWN,
                        String.format("Despawned %s (UUID=%s) – motivo: %s", species, uuid, reason));

                if (config.discordEnabled && !config.discordWebhookUrl.isEmpty()) {
                    DiscordWebhook.sendDespawn(config.discordWebhookUrl, species, reason);
                }
                break;
            }
        }
        saveStateAsync();
    }

    public void removeAll() {
        List<UUID> ids = new ArrayList<>(active.keySet());
        for (UUID id : ids) despawn(id, "removeAll por comando");
    }

    // ── Limpieza ──────────────────────────────────────────────────────────────

    public void cleanupDead() {
        List<UUID> toRemove = new ArrayList<>();
        for (UUID uuid : active.keySet()) {
            boolean found = false;
            for (ServerWorld world : server.getWorlds()) {
                Entity e = world.getEntity(uuid);
                if (e != null && !e.isRemoved()) { found = true; break; }
            }
            if (!found) toRemove.add(uuid);
        }
        for (UUID uuid : toRemove) {
            active.remove(uuid);
            cancelDespawnFuture(uuid);
            auditLogger.logAudit(AuditLogger.EventType.INFO,
                    "Entidad " + uuid + " ya no existe, eliminada del mapa activo.");
        }
        if (!toRemove.isEmpty()) saveStateAsync();
    }

    private void cancelDespawnFuture(UUID uuid) {
        ScheduledFuture<?> f = despawnFutures.remove(uuid);
        if (f != null) f.cancel(false);
    }

    // ── Captura ───────────────────────────────────────────────────────────────

    public void handleCapture(Pokemon pokemon, ServerPlayerEntity player) {
        UUID uuid = pokemon.getEntity() != null ? pokemon.getEntity().getUuid() : null;
        if (uuid == null) return;

        ActiveLegendaryEntry entry = active.remove(uuid);
        if (entry == null) return;

        cancelDespawnFuture(uuid);

        String species  = pokemon.getSpecies().getName();
        String player_n = player.getName().getString();

        String msg = config.messages.capture
                .replace("{player}", player_n)
                .replace("{pokemon}", capitalize(species));
        MessageUtil.broadcastAll(server, msg);

        auditLogger.logAudit(AuditLogger.EventType.CAPTURE,
                String.format("¡%s capturado por %s!", species, player_n));

        if (config.discordEnabled && !config.discordWebhookUrl.isEmpty()) {
            DiscordWebhook.sendCapture(config.discordWebhookUrl, species, player_n);
        }

        saveStateAsync();
    }

    // ── Estado ────────────────────────────────────────────────────────────────

    public int getActiveCount() { return active.size(); }

    public Map<UUID, ActiveLegendaryEntry> getActive() {
        return Collections.unmodifiableMap(active);
    }

    // ── Persistencia ─────────────────────────────────────────────────────────

    public void saveStateSync() {
        try {
            Files.createDirectories(STATE_DIR);
            List<StateEntry> entries = new ArrayList<>();
            for (ActiveLegendaryEntry e : active.values()) {
                entries.add(new StateEntry(e.entityUuid.toString(), e.speciesName,
                        e.spawnX, e.spawnY, e.spawnZ, e.biomeName,
                        e.spawnedAt, e.scheduledDespawnAt));
            }
            try (Writer w = Files.newBufferedWriter(STATE_FILE)) {
                GSON.toJson(entries, w);
            }
            LegendarySpawnerMod.LOGGER.info("[LegendarySpawner] Estado guardado ({} activos).", active.size());
        } catch (IOException e) {
            LegendarySpawnerMod.LOGGER.error("[LegendarySpawner] Error guardando estado", e);
        }
    }

    public void saveStateAsync() {
        List<StateEntry> snapshot = new ArrayList<>();
        for (ActiveLegendaryEntry e : active.values()) {
            snapshot.add(new StateEntry(e.entityUuid.toString(), e.speciesName,
                    e.spawnX, e.spawnY, e.spawnZ, e.biomeName,
                    e.spawnedAt, e.scheduledDespawnAt));
        }
        ioExecutor.submit(() -> {
            try {
                Files.createDirectories(STATE_DIR);
                try (Writer w = Files.newBufferedWriter(STATE_FILE)) {
                    GSON.toJson(snapshot, w);
                }
            } catch (IOException ex) {
                LegendarySpawnerMod.LOGGER.error("[LegendarySpawner] Error guardando estado async", ex);
            }
        });
    }

    public void loadState() {
        if (!Files.exists(STATE_FILE)) return;
        try (Reader r = Files.newBufferedReader(STATE_FILE)) {
            Type listType = new TypeToken<List<StateEntry>>(){}.getType();
            List<StateEntry> entries = GSON.fromJson(r, listType);
            if (entries == null) return;

            long now = System.currentTimeMillis();
            int loaded = 0, expired = 0;

            for (StateEntry se : entries) {
                if (se.scheduledDespawnAt > 0 && se.scheduledDespawnAt <= now) {
                    expired++;
                    continue;
                }
                UUID uuid = UUID.fromString(se.entityUuid);
                ActiveLegendaryEntry entry = new ActiveLegendaryEntry(
                        uuid, se.speciesName,
                        se.spawnX, se.spawnY, se.spawnZ,
                        se.biomeName, se.spawnedAt);
                entry.scheduledDespawnAt = se.scheduledDespawnAt;
                active.put(uuid, entry);

                if (se.scheduledDespawnAt > 0) {
                    long remaining = se.scheduledDespawnAt - now;
                    ScheduledFuture<?> future = scheduler.schedule(() ->
                            server.execute(() -> despawn(uuid, "despawn restaurado tras reinicio")),
                            Math.max(remaining, 5000), TimeUnit.MILLISECONDS);
                    despawnFutures.put(uuid, future);
                }
                loaded++;
            }
            LegendarySpawnerMod.LOGGER.info("[LegendarySpawner] Estado cargado: {} activos, {} expirados.", loaded, expired);
        } catch (Exception e) {
            LegendarySpawnerMod.LOGGER.error("[LegendarySpawner] Error cargando estado", e);
        }
    }

    // ── DTO interno para persistencia ─────────────────────────────────────────

    private static class StateEntry {
        String entityUuid, speciesName, biomeName;
        double spawnX, spawnY, spawnZ;
        long   spawnedAt, scheduledDespawnAt;

        StateEntry(String entityUuid, String speciesName,
                   double x, double y, double z, String biomeName,
                   long spawnedAt, long scheduledDespawnAt) {
            this.entityUuid          = entityUuid;
            this.speciesName         = speciesName;
            this.spawnX              = x;
            this.spawnY              = y;
            this.spawnZ              = z;
            this.biomeName           = biomeName;
            this.spawnedAt           = spawnedAt;
            this.scheduledDespawnAt  = scheduledDespawnAt;
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
