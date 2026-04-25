package com.example.legendaryspawner.manager;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.example.legendaryspawner.LegendarySpawnerMod;
import com.example.legendaryspawner.config.LegendaryConfig;
import com.example.legendaryspawner.util.MessageUtil;
import com.example.legendaryspawner.webhook.DiscordWebhook;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import java.util.*;
import java.util.concurrent.*;

public class SpawnScheduler {

    private final MinecraftServer        server;
    private LegendaryConfig              config;
    private final ActiveLegendaryManager activeManager;
    private final AuditLogger            auditLogger;
    private final Random                 rng = new Random();

    // El timer ahora se usa para latir cada segundo
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LegendarySpawner-Timer");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> currentTask;
    private volatile boolean   running = false;
    
    // Variables para la BossBar
    private ServerBossBar bossBar;
    private long nextSpawnTimeMs;

    public SpawnScheduler(MinecraftServer server, LegendaryConfig config,
                          ActiveLegendaryManager activeManager, AuditLogger auditLogger) {
        this.server        = server;
        this.config        = config;
        this.activeManager = activeManager;
        this.auditLogger   = auditLogger;
    }

    public void setConfig(LegendaryConfig newConfig) {
        this.config = newConfig;
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    public void start() {
        running = true;
        
        // Inicializar la BossBar visual
        bossBar = new ServerBossBar(Text.literal(""), BossBar.Color.YELLOW, BossBar.Style.PROGRESS);
        
        scheduleNext();
        
        // Ejecutar un tick cada 1 segundo exacto
        currentTask = timer.scheduleAtFixedRate(() -> {
            if (running) {
                server.execute(this::tickTimer);
            }
        }, 1, 1, TimeUnit.SECONDS);

        LegendarySpawnerMod.LOGGER.info("[LegendarySpawner] Scheduler iniciado con BossBar visual.");
    }

    public void stop() {
        running = false;
        if (currentTask != null) currentTask.cancel(false);
        if (bossBar != null) bossBar.clearPlayers();
        timer.shutdownNow();
        LegendarySpawnerMod.LOGGER.info("[LegendarySpawner] Scheduler detenido.");
    }

    public void reschedule() {
        if (currentTask != null) currentTask.cancel(false);
        if (bossBar != null) bossBar.clearPlayers();
        if (running) {
            // Reiniciar el ciclo completo en lugar de solo reprogramar
            scheduleNext();
            currentTask = timer.scheduleAtFixedRate(() -> {
                if (running) server.execute(this::tickTimer);
            }, 1, 1, TimeUnit.SECONDS);
        }
    }

    public void scheduleNext() {
        // Calcular el momento exacto en el futuro usando el intervalo de tu config.json
        nextSpawnTimeMs = System.currentTimeMillis() + (config.intervalMinutes * 60_000L);
    }

    // ── Lógica de Tiempo y BossBar ────────────────────────────────────────────

    private void tickTimer() {
        long now = System.currentTimeMillis();
        long remainingMs = nextSpawnTimeMs - now;

        // Si el tiempo llegó a cero, intentar el spawn
        if (remainingMs <= 0) {
            attemptSpawn();
            scheduleNext(); // Reiniciar el temporizador para el siguiente intento
            return;
        }

        updateBossBar(remainingMs);
    }

    private void updateBossBar(long remainingMs) {
        // Calcular formato mm:ss
        int seconds = (int) (remainingMs / 1000) % 60;
        int minutes = (int) (remainingMs / 60000);
        String timeStr = String.format("%02d:%02d", minutes, seconds);

        // Actualizar el texto
        bossBar.setName(Text.literal("§6§lPróximo intento Legendario: §e" + timeStr));
        
        // Actualizar el porcentaje de llenado (de 1.0 a 0.0)
        float progress = (float) remainingMs / (config.intervalMinutes * 60_000L);
        bossBar.setPercent(Math.max(0.0f, Math.min(1.0f, progress)));

        // Sincronizar jugadores (añadir a los que entren al servidor)
        List<ServerPlayerEntity> onlinePlayers = server.getPlayerManager().getPlayerList();
        for (ServerPlayerEntity p : onlinePlayers) {
            if (!bossBar.getPlayers().contains(p)) {
                bossBar.addPlayer(p);
            }
        }
    }

    // ── Lógica de spawn ───────────────────────────────────────────────────────

    public void attemptSpawn() {
        activeManager.cleanupDead();

        if (activeManager.getActiveCount() >= config.maxActiveLegendaries) {
            LegendarySpawnerMod.LOGGER.debug(
                    "[LegendarySpawner] Límite de activos alcanzado ({}/{}), skip.",
                    activeManager.getActiveCount(), config.maxActiveLegendaries);
            return;
        }

        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players.size() < config.minPlayers) {
            LegendarySpawnerMod.LOGGER.debug(
                    "[LegendarySpawner] Jugadores insuficientes ({}/{}), skip.",
                    players.size(), config.minPlayers);
            return;
        }

        if (rng.nextDouble() > config.spawnChance) {
            LegendarySpawnerMod.LOGGER.debug("[LegendarySpawner] Falló la probabilidad de spawn, skip.");
            return;
        }

        ServerPlayerEntity target = players.get(rng.nextInt(players.size()));
        performSpawnNearPlayer(target);
    }

    public void performSpawnNearPlayer(ServerPlayerEntity player) {
        ServerWorld world = (ServerWorld) player.getWorld();

        BlockPos playerPos = player.getBlockPos();
        String biomeId = getBiomeId(world, playerPos);

        Optional<String> speciesOpt = pickLegendaryForPlayer(world, playerPos, biomeId);
        if (speciesOpt.isEmpty()) {
            LegendarySpawnerMod.LOGGER.debug(
                    "[LegendarySpawner] Bioma '{}' no tiene legendarios configurados, cancelando.", biomeId);
            return;
        }

        String species     = speciesOpt.get();
        String properties  = species + " level=" + (50 + rng.nextInt(50));

        Optional<PokemonEntity> entityOpt =
                SpawnHelper.spawnOnServerThread(world, player, properties, config.spawnRadius);

        if (entityOpt.isEmpty()) return;

        PokemonEntity entity = entityOpt.get();

        activeManager.register(entity, biomeId);

        applyEffects(world, entity.getBlockPos(), species, biomeId);
    }

    // ── Bioma y selección ─────────────────────────────────────────────────────

    private String getBiomeId(ServerWorld world, BlockPos pos) {
        var biomeEntry = world.getBiome(pos);
        Optional<RegistryKey<Biome>> key = biomeEntry.getKey();
        return key.map(k -> k.getValue().toString()).orElse("unknown");
    }

    public Optional<String> pickLegendaryForPlayer(ServerWorld world, BlockPos pos, String biomeId) {
        List<String> pool = config.getLegendariesForBiome(biomeId);
        if (pool.isEmpty()) return Optional.empty();

        List<String> valid = pool.stream()
                .filter(s -> !config.isBlacklisted(s))
                .toList();
        if (valid.isEmpty()) return Optional.empty();

        return Optional.of(valid.get(rng.nextInt(valid.size())));
    }

    // ── Efectos y anuncios ────────────────────────────────────────────────────

    private void applyEffects(ServerWorld world, BlockPos spawnPos, String species, String biomeId) {
        if (config.lightningEffect) {
            net.minecraft.entity.LightningEntity bolt = new net.minecraft.entity.LightningEntity(
                    net.minecraft.entity.EntityType.LIGHTNING_BOLT, world);
            bolt.setCosmetic(true);
            bolt.setPosition(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
            world.spawnEntity(bolt);
        }

        if (config.witherSound) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                p.getWorld().playSound(null, p.getBlockPos(),
                        net.minecraft.sound.SoundEvents.ENTITY_WITHER_SPAWN,
                        net.minecraft.sound.SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
        }

        String cleanSpecies = species.split(" ")[0];

        String spawnMsg = config.messages.spawn
                .replace("{pokemon}", capitalize(cleanSpecies))
                .replace("{biome}", biomeId)
                .replace("{x}", String.valueOf(spawnPos.getX()))
                .replace("{y}", String.valueOf(spawnPos.getY()))
                .replace("{z}", String.valueOf(spawnPos.getZ()));
        MessageUtil.broadcastAll(server, spawnMsg);

        auditLogger.logAudit(AuditLogger.EventType.SPAWN,
                String.format("Spawneado %s en bioma %s [%d, %d, %d]",
                        species, biomeId, spawnPos.getX(), spawnPos.getY(), spawnPos.getZ()));

        if (config.discordEnabled && !config.discordWebhookUrl.isEmpty()) {
            DiscordWebhook.sendSpawn(config.discordWebhookUrl, cleanSpecies, biomeId,
                    spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
