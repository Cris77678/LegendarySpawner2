package com.example.legendaryspawner.manager;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.example.legendaryspawner.LegendarySpawnerMod;
import com.example.legendaryspawner.config.LegendaryConfig;
import com.example.legendaryspawner.util.MessageUtil;
import com.example.legendaryspawner.webhook.DiscordWebhook;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import java.util.*;
import java.util.concurrent.*;

/**
 * Controla el ciclo de aparición automática de legendarios.
 * El temporizador corre en un hilo separado; toda interacción con el mundo
 * se envía al hilo del servidor mediante {@code server.execute(...)}.
 */
public class SpawnScheduler {

    private final MinecraftServer        server;
    private final LegendaryConfig        config;
    private final ActiveLegendaryManager activeManager;
    private final AuditLogger            auditLogger;
    private final Random                 rng = new Random();

    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LegendarySpawner-Timer");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> currentTask;
    private volatile boolean   running = false;

    public SpawnScheduler(MinecraftServer server, LegendaryConfig config,
                          ActiveLegendaryManager activeManager, AuditLogger auditLogger) {
        this.server        = server;
        this.config        = config;
        this.activeManager = activeManager;
        this.auditLogger   = auditLogger;
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    public void start() {
        running = true;
        scheduleNext();
        LegendarySpawnerMod.LOGGER.info("[LegendarySpawner] Scheduler iniciado (intervalo: {} min).",
                config.intervalMinutes);
    }

    public void stop() {
        running = false;
        if (currentTask != null) currentTask.cancel(false);
        timer.shutdownNow();
        LegendarySpawnerMod.LOGGER.info("[LegendarySpawner] Scheduler detenido.");
    }

    /** Cancela el ciclo actual y programa uno nuevo según la config actual. */
    public void reschedule() {
        if (currentTask != null) currentTask.cancel(false);
        if (running) scheduleNext();
    }

    /** Programa la próxima ejecución. */
    public void scheduleNext() {
        long delay = config.intervalMinutes * 60L;
        currentTask = timer.schedule(() -> {
            // Enviamos el intento de spawn al hilo del servidor
            server.execute(this::attemptSpawn);
            // Tras ejecutarse, programar el siguiente ciclo
            if (running) scheduleNext();
        }, delay, TimeUnit.SECONDS);
    }

    // ── Lógica de spawn ───────────────────────────────────────────────────────

    /**
     * Intenta generar un legendario. Debe ejecutarse en el hilo del servidor.
     */
    public void attemptSpawn() {
        // Limpiar entidades muertas del mapa
        activeManager.cleanupDead();

        // Verificar máximo de activos
        if (activeManager.getActiveCount() >= config.maxActiveLegendaries) {
            LegendarySpawnerMod.LOGGER.debug(
                    "[LegendarySpawner] Límite de activos alcanzado ({}/{}), skip.",
                    activeManager.getActiveCount(), config.maxActiveLegendaries);
            return;
        }

        // Verificar jugadores mínimos
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players.size() < config.minPlayers) {
            LegendarySpawnerMod.LOGGER.debug(
                    "[LegendarySpawner] Jugadores insuficientes ({}/{}), skip.",
                    players.size(), config.minPlayers);
            return;
        }

        // Chance
        if (rng.nextDouble() > config.spawnChance) {
            LegendarySpawnerMod.LOGGER.debug("[LegendarySpawner] Falló la probabilidad de spawn, skip.");
            return;
        }

        // Elegir un jugador al azar como punto de referencia
        ServerPlayerEntity target = players.get(rng.nextInt(players.size()));
        performSpawnNearPlayer(target);
    }

    /**
     * Genera un legendario cerca del jugador dado.
     * Se ejecuta en el hilo del servidor.
     */
    public void performSpawnNearPlayer(ServerPlayerEntity player) {
        ServerWorld world = (ServerWorld) player.getWorld();

        // Obtener bioma del jugador
        BlockPos playerPos = player.getBlockPos();
        String biomeId = getBiomeId(world, playerPos);

        // Elegir legendario según bioma
        Optional<String> speciesOpt = pickLegendaryForPlayer(world, playerPos, biomeId);
        if (speciesOpt.isEmpty()) {
            LegendarySpawnerMod.LOGGER.debug(
                    "[LegendarySpawner] Bioma '{}' no tiene legendarios configurados, cancelando.", biomeId);
            return;
        }

        String species     = speciesOpt.get();
        String properties  = species + " level=" + (50 + rng.nextInt(50)); // nivel aleatorio 50-99

        Optional<PokemonEntity> entityOpt =
                SpawnHelper.spawnOnServerThread(world, player, properties, config.spawnRadius);

        if (entityOpt.isEmpty()) return;

        PokemonEntity entity = entityOpt.get();

        // Registrar en el manager
        activeManager.register(entity, biomeId);

        // Efectos y anuncios
        applyEffects(world, entity.getBlockPos(), species, biomeId);
    }

    // ── Bioma y selección ─────────────────────────────────────────────────────

    /**
     * Devuelve el identificador del bioma en la posición dada.
     */
    private String getBiomeId(ServerWorld world, BlockPos pos) {
        var biomeEntry = world.getBiome(pos);
        Optional<RegistryKey<Biome>> key = biomeEntry.getKey();
        return key.map(k -> k.getValue().toString()).orElse("unknown");
    }

    /**
     * Selecciona el legendario para el bioma.
     * Si el bioma no está configurado, devuelve vacío (sin fallback).
     */
    public Optional<String> pickLegendaryForPlayer(ServerWorld world, BlockPos pos, String biomeId) {
        List<String> pool = config.getLegendariesForBiome(biomeId);
        if (pool.isEmpty()) return Optional.empty();

        // Filtrar blacklist
        List<String> valid = pool.stream()
                .filter(s -> !config.isBlacklisted(s))
                .toList();
        if (valid.isEmpty()) return Optional.empty();

        return Optional.of(valid.get(rng.nextInt(valid.size())));
    }

    // ── Efectos y anuncios ────────────────────────────────────────────────────

    private void applyEffects(ServerWorld world, BlockPos spawnPos, String species, String biomeId) {
        // Rayo cosmético
        if (config.lightningEffect) {
            net.minecraft.entity.LightningEntity bolt = new net.minecraft.entity.LightningEntity(
                    net.minecraft.entity.EntityType.LIGHTNING_BOLT, world);
            bolt.setCosmetic(true); // sin daño
            bolt.setPosition(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
            world.spawnEntity(bolt);
        }

        // Sonido de Wither (global)
        if (config.witherSound) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                p.getWorld().playSound(null, p.getBlockPos(),
                        net.minecraft.sound.SoundEvents.ENTITY_WITHER_SPAWN,
                        net.minecraft.sound.SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
        }

        // Anuncio al servidor
        String spawnMsg = config.messages.spawn
                .replace("{pokemon}", capitalize(species))
                .replace("{biome}", biomeId)
                .replace("{x}", String.valueOf(spawnPos.getX()))
                .replace("{y}", String.valueOf(spawnPos.getY()))
                .replace("{z}", String.valueOf(spawnPos.getZ()));
        MessageUtil.broadcastAll(server, spawnMsg);

        auditLogger.logAudit(AuditLogger.EventType.SPAWN,
                String.format("Spawneado %s en bioma %s [%d, %d, %d]",
                        species, biomeId, spawnPos.getX(), spawnPos.getY(), spawnPos.getZ()));

        // Discord
        if (config.discordEnabled && !config.discordWebhookUrl.isEmpty()) {
            DiscordWebhook.sendSpawn(config.discordWebhookUrl, species, biomeId,
                    spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
