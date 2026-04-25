package com.example.legendaryspawner.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Utilidades para envío de mensajes a jugadores y broadcast global.
 */
public final class MessageUtil {

    private MessageUtil() {}

    /**
     * Envía un mensaje a todos los jugadores del servidor.
     * Procesa códigos de color tipo §.
     */
    public static void broadcastAll(MinecraftServer server, String message) {
        Text text = colorize(message);
        server.getPlayerManager().broadcast(text, false);
    }

    /**
     * Envía un mensaje a un jugador específico.
     */
    public static void sendMsg(ServerPlayerEntity player, String message) {
        player.sendMessage(colorize(message), false);
    }

    /**
     * Convierte códigos §X a formato de texto Minecraft.
     */
    public static Text colorize(String message) {
        // Minecraft acepta el formato legacy §
        return Text.literal(message);
    }
}
