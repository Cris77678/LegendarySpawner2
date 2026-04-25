package com.example.legendaryspawner.command;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.example.legendaryspawner.LegendarySpawnerMod;
import com.example.legendaryspawner.manager.ActiveLegendaryEntry;
import com.example.legendaryspawner.manager.AuditLogger;
import com.example.legendaryspawner.manager.SpawnHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registro y lógica de los comandos /legendaryspawner y /ls.
 *
 * Subcomandos:
 *   spawn                   – genera un legendario automático cerca del ejecutor
 *   spawn <properties>      – genera un Pokémon con propiedades exactas
 *   remove                  – elimina todos los legendarios activos
 *   status                  – muestra el estado actual
 *   history [n]             – muestra las últimas N entradas de auditoría
 *   reload                  – recarga la configuración
 */
public final class LegendarySpawnerCommand {

    private LegendarySpawnerCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(buildRoot("legendaryspawner"));
        dispatcher.register(buildRoot("ls"));
    }

    /**
     * Construye y devuelve el literal raíz con todos los subcomandos.
     * Se usa tanto para /legendaryspawner como para /ls.
     */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildRoot(String label) {
        return CommandManager.literal(label)
                .requires(LegendarySpawnerCommand::hasPermission)

                // spawn (auto)
                .then(CommandManager.literal("spawn")
                        .executes(LegendarySpawnerCommand::executeSpawnAuto)
                        .then(CommandManager.argument("properties", StringArgumentType.greedyString())
                                .executes(ctx -> executeSpawnProperties(
                                        ctx,
                                        StringArgumentType.getString(ctx, "properties")
                                ))))

                // remove
                .then(CommandManager.literal("remove")
                        .executes(LegendarySpawnerCommand::executeRemove))

                // status
                .then(CommandManager.literal("status")
                        .executes(LegendarySpawnerCommand::executeStatus))

                // history [n]
                .then(CommandManager.literal("history")
                        .executes(ctx -> executeHistory(ctx, 10))
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> executeHistory(
                                        ctx,
                                        IntegerArgumentType.getInteger(ctx, "count")
                                ))))

                // reload
                .then(CommandManager.literal("reload")
                        .executes(LegendarySpawnerCommand::executeReload));
    }

    // ── Ejecutores ────────────────────────────────────────────────────────────

    /** /ls spawn  – genera un legendario automático cerca del ejecutor */
    private static int executeSpawnAuto(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) {
            src.sendError(Text.literal("§cEste comando debe ejecutarse como jugador."));
            return 0;
        }

        var scheduler = LegendarySpawnerMod.getScheduler();
        if (scheduler == null) {
            src.sendError(Text.literal("§cEl scheduler no está disponible aún."));
            return 0;
        }

        scheduler.performSpawnNearPlayer(player);
        src.sendFeedback(() -> Text.literal("§aIntento de spawn forzado ejecutado."), true);

        AuditLogger logger = LegendarySpawnerMod.getAuditLogger();
        if (logger != null) {
            logger.logAudit(AuditLogger.EventType.FORCED_SPAWN,
                    "Spawn forzado por " + src.getName());
        }
        return 1;
    }

    /** /ls spawn <properties>  – spawn directo con propiedades */
    private static int executeSpawnProperties(CommandContext<ServerCommandSource> ctx, String properties) {
        ServerCommandSource src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) {
            src.sendError(Text.literal("§cEste comando debe ejecutarse como jugador."));
            return 0;
        }

        var world = player.getServerWorld();
        var config = LegendarySpawnerMod.getConfig();
        var manager = LegendarySpawnerMod.getActiveManager();

        Optional<PokemonEntity> entityOpt = SpawnHelper.spawnOnServerThread(
                world,
                player,
                properties,
                config.spawnRadius
        );

        if (entityOpt.isEmpty()) {
            src.sendError(Text.literal("§cNo se pudo generar el Pokémon. Revisa los logs."));
            return 0;
        }

        if (manager != null) {
            manager.register(entityOpt.get(), "comando");
        }

        src.sendFeedback(() -> Text.literal("§aGenerado: §f" + properties), true);

        AuditLogger logger = LegendarySpawnerMod.getAuditLogger();
        if (logger != null) {
            logger.logAudit(AuditLogger.EventType.FORCED_SPAWN,
                    src.getName() + " generó: " + properties);
        }
        return 1;
    }

    /** /ls remove  – elimina todos los legendarios activos */
    private static int executeRemove(CommandContext<ServerCommandSource> ctx) {
        var manager = LegendarySpawnerMod.getActiveManager();
        if (manager == null) {
            ctx.getSource().sendError(Text.literal("§cManager no disponible."));
            return 0;
        }

        int count = manager.getActiveCount();
        manager.removeAll();

        ctx.getSource().sendFeedback(
                () -> Text.literal("§aEliminados §f" + count + "§a legendarios activos."), true);

        AuditLogger logger = LegendarySpawnerMod.getAuditLogger();
        if (logger != null) {
            logger.logAudit(AuditLogger.EventType.INFO,
                    ctx.getSource().getName() + " eliminó " + count + " legendarios.");
        }
        return 1;
    }

    /** /ls status  – estado actual */
    private static int executeStatus(CommandContext<ServerCommandSource> ctx) {
        var manager = LegendarySpawnerMod.getActiveManager();
        var config = LegendarySpawnerMod.getConfig();
        if (manager == null || config == null) {
            ctx.getSource().sendError(Text.literal("§cManager o configuración no disponible."));
            return 0;
        }

        ctx.getSource().sendFeedback(() -> Text.literal(
                "§6=== LegendarySpawner Status ===\n" +
                "§eActivos: §f" + manager.getActiveCount() + "/" + config.maxActiveLegendaries + "\n" +
                "§eIntervalo: §f" + config.intervalMinutes + " min\n" +
                "§eDespawn: §f" + config.despawnMinutes + " min\n" +
                "§eJugadores mínimos: §f" + config.minPlayers + "\n" +
                "§eProbabilidad: §f" + (int) (config.spawnChance * 100) + "%\n" +
                "§eDiscord: §f" + (config.discordEnabled ? "✔ habilitado" : "✘ deshabilitado")
        ), false);

        Map<UUID, ActiveLegendaryEntry> actives = manager.getActive();
        if (!actives.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("§eLegendarios activos:"), false);
            for (ActiveLegendaryEntry e : actives.values()) {
                ctx.getSource().sendFeedback(() -> Text.literal(
                        "  §f- " + e.speciesName + " §7en " + e.biomeName +
                        " [" + (int) e.spawnX + ", " + (int) e.spawnY + ", " + (int) e.spawnZ + "]"
                ), false);
            }
        }
        return 1;
    }

    /** /ls history [n]  – últimas entradas de auditoría */
    private static int executeHistory(CommandContext<ServerCommandSource> ctx, int count) {
        var logger = LegendarySpawnerMod.getAuditLogger();
        if (logger == null) {
            ctx.getSource().sendError(Text.literal("§cLogger no disponible."));
            return 0;
        }

        var entries = logger.getLastEntries(count);
        ctx.getSource().sendFeedback(() -> Text.literal(
                "§6=== Historial (últimas " + count + " entradas) ==="), false);

        for (String line : entries) {
            ctx.getSource().sendFeedback(() -> Text.literal("§7" + line), false);
        }

        if (entries.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("§7(sin registros)"), false);
        }
        return 1;
    }

    /** /ls reload  – recarga configuración */
    private static int executeReload(CommandContext<ServerCommandSource> ctx) {
        LegendarySpawnerMod.reloadConfig();
        ctx.getSource().sendFeedback(
                () -> Text.literal("§aConfiguración recargada correctamente."), true);
        return 1;
    }

    // ── Permisos ──────────────────────────────────────────────────────────────

    private static boolean hasPermission(ServerCommandSource src) {
        if (src.hasPermissionLevel(2)) return true;

        try {
            net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();

            if (src.getEntity() instanceof ServerPlayerEntity player) {
                net.luckperms.api.model.user.User user = lp.getUserManager().getUser(player.getUuid());
                if (user != null) {
                    return user.getCachedData().getPermissionData()
                            .checkPermission("legendaryspawner.admin")
                            .asBoolean();
                }
            }
        } catch (IllegalStateException | NoClassDefFoundError ignored) {
            // LuckPerms no disponible, fallback a nivel OP
        }

        return false;
    }
}