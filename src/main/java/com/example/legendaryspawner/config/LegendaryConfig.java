package com.example.legendaryspawner.config;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import com.example.legendaryspawner.LegendarySpawnerMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Configuración principal del mod. Se persiste en config/legendaryspawner/config.json
 */
public class LegendaryConfig {

    private static final Path CONFIG_DIR  = FabricLoader.getInstance().getConfigDir().resolve("legendaryspawner");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Scheduler ─────────────────────────────────────────────────────────────

    @SerializedName("intervalo_minutos")
    public int intervalMinutes = 30;

    @SerializedName("probabilidad_spawn")
    public double spawnChance = 0.80;

    @SerializedName("minimo_jugadores")
    public int minPlayers = 3;

    @SerializedName("radio_spawn")
    public int spawnRadius = 64;

    @SerializedName("max_legendarios_activos")
    public int maxActiveLegendaries = 1;

    @SerializedName("minutos_despawn")
    public int despawnMinutes = 15;

    // ── Biomas y listas ───────────────────────────────────────────────────────

    /**
     * Mapa bioma → lista de Pokémon permitidos.
     * SOLO se generan Pokémon en biomas que aparezcan aquí.
     */
    @SerializedName("biomas")
    public Map<String, List<String>> biomes = new LinkedHashMap<>();

    /** Lista negra global: estas especies NUNCA se generan. */
    @SerializedName("blacklist")
    public List<String> blacklist = new ArrayList<>();

    // ── Discord ───────────────────────────────────────────────────────────────

    @SerializedName("discord_webhook_url")
    public String discordWebhookUrl = "";

    @SerializedName("discord_habilitado")
    public boolean discordEnabled = false;

    // ── Efectos ───────────────────────────────────────────────────────────────

    @SerializedName("efecto_rayo")
    public boolean lightningEffect = true;

    @SerializedName("sonido_wither")
    public boolean witherSound = true;

    @SerializedName("anunciar_coordenadas")
    public boolean announceCoords = true;

    // ── Mensajes ──────────────────────────────────────────────────────────────

    @SerializedName("mensajes")
    public Messages messages = new Messages();

    public static class Messages {
        @SerializedName("spawn")
        public String spawn = "§6§l¡Un {pokemon} legendario ha aparecido en {biome}! Coordenadas: {x}, {y}, {z}";

        @SerializedName("captura")
        public String capture = "§b§l¡{player} ha capturado a {pokemon}!";

        @SerializedName("despawn")
        public String despawn = "§7El legendario {pokemon} ha desaparecido...";

        @SerializedName("sin_jugadores")
        public String noPlayers = "§cNo hay suficientes jugadores en línea para generar un legendario.";

        @SerializedName("max_activos")
        public String maxActive = "§cYa hay un legendario activo en el servidor.";
    }

    // ── Carga y guardado ──────────────────────────────────────────────────────

    public static LegendaryConfig loadOrCreate() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            LegendarySpawnerMod.LOGGER.error("[LegendarySpawner] No se pudo crear el directorio de configuración", e);
        }

        if (Files.exists(CONFIG_FILE)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
                LegendaryConfig cfg = GSON.fromJson(reader, LegendaryConfig.class);
                if (cfg != null) {
                    LegendarySpawnerMod.LOGGER.info("[LegendarySpawner] Configuración cargada desde {}", CONFIG_FILE);
                    return cfg;
                }
            } catch (Exception e) {
                LegendarySpawnerMod.LOGGER.error("[LegendarySpawner] Error leyendo config.json, usando defaults.", e);
            }
        }

        // Primera vez: crear configuración de ejemplo
        LegendaryConfig cfg = new LegendaryConfig();
        cfg.setDefaults();
        cfg.save();
        LegendarySpawnerMod.LOGGER.info("[LegendarySpawner] Configuración por defecto creada en {}", CONFIG_FILE);
        return cfg;
    }

    private void setDefaults() {
        blacklist.add("eternatus");
        blacklist.add("necrozma");

        biomes.put("minecraft:plains",   List.of("entei", "raikou", "suicune", "ho_oh"));
        biomes.put("minecraft:desert",   List.of("groudon", "regirock"));
        biomes.put("minecraft:ocean",    List.of("kyogre", "lugia"));
        biomes.put("minecraft:forest",   List.of("celebi", "virizion"));
        biomes.put("minecraft:mountain", List.of("regice", "registeel", "regirock"));
        biomes.put("minecraft:snowy_plains", List.of("articuno", "regice"));
        biomes.put("minecraft:jungle",   List.of("mew", "celebi", "virizion"));
        biomes.put("minecraft:taiga",    List.of("articuno", "suicune"));
        biomes.put("nether_wastes",      List.of("reshiram", "zekrom"));
    }

    public void save() {
        try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            LegendarySpawnerMod.LOGGER.error("[LegendarySpawner] Error guardando config.json", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Devuelve la lista de Pokémon para un bioma concreto.
     * Si el bioma no está configurado, devuelve lista vacía (sin fallback).
     */
    public List<String> getLegendariesForBiome(String biomeId) {
        // Búsqueda directa (ej. "minecraft:plains")
        List<String> direct = biomes.get(biomeId);
        if (direct != null && !direct.isEmpty()) return direct;

        // Búsqueda por sufijo (ej. "plains" en "minecraft:plains")
        String suffix = biomeId.contains(":") ? biomeId.substring(biomeId.indexOf(':') + 1) : biomeId;
        for (Map.Entry<String, List<String>> entry : biomes.entrySet()) {
            String key = entry.getKey();
            String keySuffix = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            if (keySuffix.equalsIgnoreCase(suffix)) return entry.getValue();
        }

        return Collections.emptyList(); // bioma no configurado → sin spawn
    }

    public boolean isBlacklisted(String speciesName) {
        return blacklist.stream().anyMatch(b -> b.equalsIgnoreCase(speciesName));
    }
}
