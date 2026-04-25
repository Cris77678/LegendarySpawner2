package com.example.legendaryspawner.webhook;

import com.example.legendaryspawner.LegendarySpawnerMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Envía mensajes a Discord mediante webhooks.
 * Todas las peticiones son asíncronas para no bloquear el hilo principal.
 */
public final class DiscordWebhook {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LegendarySpawner-Discord");
        t.setDaemon(true);
        return t;
    });

    private DiscordWebhook() {}

    // ── Métodos públicos ──────────────────────────────────────────────────────

    public static void sendSpawn(String webhookUrl, String species, String biome,
                                  int x, int y, int z) {
        JsonObject embed = buildEmbed(
                "✨ ¡Legendario aparecido!",
                String.format("**%s** ha aparecido en el bioma `%s`\nCoordenadas: `%d, %d, %d`",
                        capitalize(species), biome, x, y, z),
                0xFFD700 // dorado
        );
        sendAsync(webhookUrl, embed);
    }

    public static void sendCapture(String webhookUrl, String species, String playerName) {
        JsonObject embed = buildEmbed(
                "🎉 ¡Legendario capturado!",
                String.format("**%s** ha sido capturado por **%s**.",
                        capitalize(species), playerName),
                0x00BFFF // azul
        );
        sendAsync(webhookUrl, embed);
    }

    public static void sendDespawn(String webhookUrl, String species, String reason) {
        JsonObject embed = buildEmbed(
                "💨 Legendario despawneado",
                String.format("**%s** ha desaparecido. Motivo: %s", capitalize(species), reason),
                0x808080 // gris
        );
        sendAsync(webhookUrl, embed);
    }

    // ── Internos ──────────────────────────────────────────────────────────────

    private static JsonObject buildEmbed(String title, String description, int color) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title",       title);
        embed.addProperty("description", description);
        embed.addProperty("color",       color);

        // Timestamp ISO 8601
        embed.addProperty("timestamp",
                java.time.Instant.now().toString());

        JsonObject footer = new JsonObject();
        footer.addProperty("text", "LegendarySpawner");
        embed.add("footer", footer);
        return embed;
    }

    private static void sendAsync(String webhookUrl, JsonObject embed) {
        EXECUTOR.submit(() -> sendDiscordWebhook(webhookUrl, embed));
    }

    /**
     * Realiza la petición HTTP al webhook de Discord.
     */
    public static void sendDiscordWebhook(String webhookUrl, JsonObject embed) {
        try {
            JsonArray embeds = new JsonArray();
            embeds.add(embed);

            JsonObject payload = new JsonObject();
            payload.add("embeds", embeds);

            String json = payload.toString();
            URL url = URI.create(webhookUrl).toURL();
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            
            // 👇 SOLUCIÓN: User-Agent añadido para evitar que Discord bloquee la petición
            con.setRequestProperty("User-Agent", "LegendarySpawner-Mod/1.0");

            con.setConnectTimeout(5_000);
            con.setReadTimeout(5_000);

            try (OutputStream os = con.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            if (code == 429) {
                LegendarySpawnerMod.LOGGER.warn("[LegendarySpawner] Discord Rate Limit excedido (Error 429). Demasiadas peticiones.");
            } else if (code < 200 || code >= 300) {
                LegendarySpawnerMod.LOGGER.warn(
                        "[LegendarySpawner] Discord webhook respondió con código {}", code);
            }
            con.disconnect();
        } catch (Exception e) {
            LegendarySpawnerMod.LOGGER.error("[LegendarySpawner] Error enviando webhook a Discord", e);
        }
    }

    /**
     * Apaga el ejecutor de forma segura.
     */
    public static void shutdown() {
        EXECUTOR.shutdown();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
