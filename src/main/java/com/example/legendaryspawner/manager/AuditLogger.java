package com.example.legendaryspawner.manager;

import com.example.legendaryspawner.LegendarySpawnerMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Gestiona el log de auditoría: spawns, despawns, capturas y errores.
 * Las escrituras a disco se hacen de forma asíncrona para no bloquear el hilo principal.
 */
public class AuditLogger {

    public enum EventType { SPAWN, DESPAWN, CAPTURE, FORCED_SPAWN, ERROR, INFO }

    public record AuditEntry(LocalDateTime time, EventType type, String message) {
        @Override public String toString() {
            return String.format("[%s] [%s] %s",
                    time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    type.name(), message);
        }
    }

    private static final Path LOG_FILE = FabricLoader.getInstance()
            .getGameDir().resolve("logs").resolve("legendary_history.log");

    private final List<AuditEntry>        history    = new CopyOnWriteArrayList<>();
    private final ExecutorService         ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LegendarySpawner-IO");
        t.setDaemon(true);
        return t;
    });

    public AuditLogger() {
        try { Files.createDirectories(LOG_FILE.getParent()); }
        catch (IOException ignored) {}
    }

    /** Registra un evento y lo persiste de forma asíncrona. */
    public void logAudit(EventType type, String message) {
        AuditEntry entry = new AuditEntry(LocalDateTime.now(), type, message);
        history.add(entry);
        // Limitar historial en memoria
        while (history.size() > 500) history.remove(0);

        LegendarySpawnerMod.LOGGER.info("[Audit][{}] {}", type, message);

        // Escritura asíncrona a disco
        ioExecutor.submit(() -> {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    LOG_FILE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(entry.toString());
                writer.newLine();
            } catch (IOException e) {
                LegendarySpawnerMod.LOGGER.error("[LegendarySpawner] Error escribiendo audit log", e);
            }
        });
    }

    public List<AuditEntry> getAuditHistory() {
        return Collections.unmodifiableList(history);
    }

    /** Devuelve las últimas N entradas como texto formateado. */
    public List<String> getLastEntries(int n) {
        List<AuditEntry> all = getAuditHistory();
        int from = Math.max(0, all.size() - n);
        List<String> result = new ArrayList<>();
        for (int i = from; i < all.size(); i++) {
            result.add(all.get(i).toString());
        }
        return result;
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try { ioExecutor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
