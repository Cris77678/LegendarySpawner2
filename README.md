# LegendarySpawner

Sistema profesional de aparición de Pokémon legendarios para servidores **Cobblemon 1.7.3+1.21.1** (Fabric).

---

## Requisitos

| Componente | Versión |
|---|---|
| Java | 21+ |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16.9+ |
| Fabric API | 0.102.0+1.21.1 |
| Cobblemon | 1.7.3+1.21.1 |
| LuckPerms *(opcional)* | 5.x |

---

## Compilación

```bash
# Clonar repositorio
git clone https://github.com/tuusuario/legendaryspawner.git
cd legendaryspawner

# Compilar
./gradlew build

# El .jar final estará en:
build/libs/legendaryspawner-1.0.0.jar
```

---

## Instalación

1. Copiar `legendaryspawner-1.0.0.jar` a la carpeta `mods/` del servidor.
2. Iniciar el servidor una vez para generar la configuración.
3. Editar `config/legendaryspawner/config.json` según tus necesidades.
4. Reiniciar el servidor o usar `/ls reload`.

---

## Configuración

La configuración se genera automáticamente en `config/legendaryspawner/config.json`.

### Ejemplo completo

```json
{
  "intervalo_minutos": 30,
  "probabilidad_spawn": 0.80,
  "minimo_jugadores": 3,
  "radio_spawn": 64,
  "max_legendarios_activos": 1,
  "minutos_despawn": 15,
  "biomas": {
    "minecraft:plains": ["entei", "raikou", "suicune", "ho_oh"],
    "minecraft:desert": ["groudon", "regirock"],
    "minecraft:ocean": ["kyogre", "lugia"],
    "minecraft:forest": ["celebi", "virizion"],
    "minecraft:snowy_plains": ["articuno", "regice"],
    "minecraft:jungle": ["mew", "celebi"],
    "minecraft:taiga": ["articuno", "suicune"],
    "nether_wastes": ["reshiram", "zekrom"]
  },
  "blacklist": ["eternatus", "necrozma"],
  "discord_webhook_url": "https://discord.com/api/webhooks/...",
  "discord_habilitado": false,
  "efecto_rayo": true,
  "sonido_wither": true,
  "anunciar_coordenadas": true,
  "mensajes": {
    "spawn": "§6§l¡Un {pokemon} legendario ha aparecido en {biome}! Coordenadas: {x}, {y}, {z}",
    "captura": "§b§l¡{player} ha capturado a {pokemon}!",
    "despawn": "§7El legendario {pokemon} ha desaparecido...",
    "sin_jugadores": "§cNo hay suficientes jugadores para generar un legendario.",
    "max_activos": "§cYa hay un legendario activo en el servidor."
  }
}
```

### Nota importante sobre biomas

**Solo aparecen legendarios en biomas explícitamente listados.**  
Si un bioma NO está en la configuración, no se generará ningún Pokémon — sin fallback.

---

## Comandos

| Comando | Descripción |
|---|---|
| `/ls spawn` | Genera un legendario automático cerca de ti |
| `/ls spawn <propiedades>` | Genera un Pokémon con propiedades exactas |
| `/ls remove` | Elimina todos los legendarios activos |
| `/ls status` | Muestra el estado actual del sistema |
| `/ls history [n]` | Últimas N entradas del log de auditoría |
| `/ls reload` | Recarga la configuración desde disco |

**Permiso requerido:** nivel OP 2 o nodo LuckPerms `legendaryspawner.admin`.

### Ejemplos de propiedades para spawn

```
/ls spawn mewtwo
/ls spawn mewtwo level=70
/ls spawn mewtwo level=100 shiny nature=modest
/ls spawn ho_oh level=80
```

---

## Archivos generados

| Ruta | Descripción |
|---|---|
| `config/legendaryspawner/config.json` | Configuración principal |
| `config/legendaryspawner/active_state.json` | Estado persistente de legendarios activos |
| `logs/legendary_history.log` | Log de auditoría (spawns, capturas, despawns) |

---

## Funcionalidades

### Bloqueo de spawns naturales
Todos los Pokémon con label `legendary`, `mythical` o `ultra-beast` que NO hayan sido generados por el mod se cancelan automáticamente.

### Scheduler automático
Cada N minutos (configurable), el sistema:
1. Verifica jugadores en línea.
2. Verifica límite de activos.
3. Aplica probabilidad de spawn.
4. Detecta el bioma del jugador objetivo.
5. Elige un legendario del pool del bioma.
6. Busca una posición segura (válida tanto en Overworld como en Nether).
7. Genera el Pokémon, lo registra y programa su despawn.

### Persistencia entre reinicios
El estado de los legendarios activos se guarda en `active_state.json`. Al reiniciar, los despawns pendientes se reprograman correctamente.

### Discord Webhooks
Los eventos de spawn, captura y despawn se envían de forma asíncrona a un webhook de Discord con embeds formateados.

---

## Arquitectura

```
src/main/java/com/example/legendaryspawner/
├── LegendarySpawnerMod.java          ← Inicializador principal
├── command/
│   └── LegendarySpawnerCommand.java  ← Comandos /ls y /legendaryspawner
├── config/
│   └── LegendaryConfig.java          ← Sistema de configuración JSON
├── manager/
│   ├── ActiveLegendaryEntry.java     ← DTO de legendario activo
│   ├── ActiveLegendaryManager.java   ← Gestión de activos + persistencia
│   ├── AuditLogger.java              ← Log de auditoría asíncrono
│   ├── SpawnHelper.java              ← Spawn seguro de entidades
│   └── SpawnScheduler.java           ← Ciclo automático de spawn
├── util/
│   └── MessageUtil.java              ← Utilidades de mensajes
└── webhook/
    └── DiscordWebhook.java           ← Integración Discord async
```

---

## Thread Safety

- **Todas** las interacciones con el mundo y entidades ocurren en el hilo del servidor (via `server.execute(...)`).
- El scheduler, los despawns y los webhooks corren en hilos daemon separados.
- La escritura a disco (config, estado, audit log) es completamente asíncrona.

---

## Licencia

MIT — libre uso en servidores públicos y privados.
