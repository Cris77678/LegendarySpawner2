package com.example.legendaryspawner;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.example.legendaryspawner.command.LegendarySpawnerCommand;
import com.example.legendaryspawner.config.LegendaryConfig;
import com.example.legendaryspawner.manager.ActiveLegendaryManager;
import com.example.legendaryspawner.manager.AuditLogger;
import com.example.legendaryspawner.manager.SpawnScheduler;
import com.example.legendaryspawner.webhook.DiscordWebhook;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LegendarySpawnerMod implements ModInitializer {

    public static final String MOD_ID = "legendaryspawner";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final String SPAWNER_TAG = "legendaryspawner_id";

    private static final Set<UUID> spawnedByMod = ConcurrentHashMap.newKeySet();

    private static MinecraftServer server;
    private static LegendaryConfig config;
    private static ActiveLegendaryManager activeManager;
    private static SpawnScheduler scheduler;
    private static AuditLogger auditLogger;

    @Override
    public void onInitialize() {
        LOGGER.info("[LegendarySpawner] Inicializando mod...");

        config = LegendaryConfig.loadOrCreate();
        auditLogger = new AuditLogger();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                LegendarySpawnerCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            server = srv;
            activeManager = new ActiveLegendaryManager(srv, config, auditLogger);
            activeManager.loadState();
            scheduler = new SpawnScheduler(srv, config, activeManager, auditLogger);
            scheduler.start();
            registerCobblemonEvents();
            LOGGER.info("[LegendarySpawner] Mod iniciado correctamente.");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            if (scheduler != null) scheduler.stop();
            if (activeManager != null) activeManager.saveStateSync();
            DiscordWebhook.shutdown(); // Apagado limpio del webhook
            spawnedByMod.clear();
            LOGGER.info("[LegendarySpawner] Mod detenido. Estado guardado.");
        });
    }

    private void registerCobblemonEvents() {
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(event -> {
            PokemonEntity entity = event.getEntity();
            Pokemon pokemon = entity.getPokemon();

            if (wasSpawnedByMod(entity.getUuid())) {
                return;
            }

            if (isLegendaryCategory(pokemon)) {
                event.cancel();
                LOGGER.debug("[LegendarySpawner] Spawn natural bloqueado: {}", pokemon.getSpecies().getName());
            }
        });

        CobblemonEvents.POKEMON_CAPTURED.subscribe(event -> {
            if (activeManager != null) {
                activeManager.handleCapture(event.getPokemon(), event.getPlayer());
            }
        });
    }

    public static boolean isLegendaryCategory(Pokemon pokemon) {
        var labels = pokemon.getSpecies().getLabels();
        return labels.contains("legendary")
                || labels.contains("mythical")
                || labels.contains("ultra-beast");
    }

    public static void markSpawnedByMod(UUID uuid) {
        if (uuid != null) {
            spawnedByMod.add(uuid);
        }
    }

    public static void unmarkSpawnedByMod(UUID uuid) {
        if (uuid != null) {
            spawnedByMod.remove(uuid);
        }
    }

    public static boolean wasSpawnedByMod(UUID uuid) {
        return uuid != null && spawnedByMod.contains(uuid);
    }

    public static MinecraftServer getServer() { return server; }
    public static LegendaryConfig getConfig() { return config; }
    public static ActiveLegendaryManager getActiveManager() { return activeManager; }
    public static SpawnScheduler getScheduler() { return scheduler; }
    public static AuditLogger getAuditLogger() { return auditLogger; }

    public static void reloadConfig() {
        config = LegendaryConfig.loadOrCreate();
        
        if (activeManager != null) {
            activeManager.setConfig(config);
        }
        if (scheduler != null) {
            scheduler.setConfig(config);
            scheduler.reschedule();
        }
        
        LOGGER.info("[LegendarySpawner] Configuración recargada y aplicada al Scheduler.");
    }
}
