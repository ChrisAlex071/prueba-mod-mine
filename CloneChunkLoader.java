package dev.playerclones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Carga de chunks de los clones, independiente de la distancia al jugador.
 *
 * Se utiliza setChunkForced para que los chunks tambien vuelvan a cargarse tras
 * reiniciar el mundo. A diferencia de los tickets temporales de las ender pearls,
 * los forceload se guardan en los datos del mundo.
 *
 * Los chunks ya forzados por /forceload (u otro sistema) NO se liberan al quitar
 * el clon. Los chunks creados por este mod se comparten mediante referencias, de
 * manera que dos clones cercanos no se quiten mutuamente la zona de trabajo.
 *
 * Se guarda en los tags de cada entidad que chunks eran propiedad del mod, para
 * recuperar esa informacion despues de reiniciar el servidor.
 */
final class CloneChunkLoader {
    /** 2 cubre completamente los 32 bloques de busqueda de aldeanos (5x5). */
    static final int WORK_RADIUS_CHUNKS = 2;
    /** Sin tarea solo conservamos activo el propio chunk del clon. */
    static final int IDLE_RADIUS_CHUNKS = 0;

    static final String TAG_MANAGED_CHUNKS = "playerclones:managed_chunks=";

    private static final class Claim {
        final Set<UUID> holders = new HashSet<>();
        boolean managedByMod;

        Claim(boolean managedByMod) {
            this.managedByMod = managedByMod;
        }
    }

    private static final class Active {
        final ServerLevel level;
        final Set<Long> held = new HashSet<>();
        final Set<Long> restoredManaged;

        Active(ServerLevel level, Set<Long> restoredManaged) {
            this.level = level;
            this.restoredManaged = restoredManaged;
        }
    }

    private static final Map<ServerLevel, Map<Long, Claim>> CLAIMS = new IdentityHashMap<>();
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    private CloneChunkLoader() {}

    /** Llamar al registrar un clon, al cambiar su tarea y despues de moverlo. */
    static void refresh(CloneWorker worker) {
        if (worker.isRemoved()) {
            release(worker);
            return;
        }

        UUID id = worker.uuid();
        ServerLevel level = worker.serverLevel();
        Active active = ACTIVE.get(id);
        if (active != null && active.level != level) {
            release(worker);
            active = null;
        }
        if (active == null) {
            active = new Active(level, readManaged(worker));
            ACTIVE.put(id, active);
        }

        ChunkPos center = worker.chunkPos();
        int radius = worker.hasTask() ? WORK_RADIUS_CHUNKS : IDLE_RADIUS_CHUNKS;
        Set<Long> desired = new HashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                desired.add(ChunkPos.asLong(center.x + dx, center.z + dz));
            }
        }

        // Primero forzar los nuevos, luego soltar los antiguos: no hay ventana
        // donde el clon se quede sin un chunk cargado cuando cruza el limite.
        for (long key : desired) {
            if (!active.held.contains(key)) {
                claim(level, key, id, active.restoredManaged.contains(key));
                active.held.add(key);
            }
        }
        for (long key : new HashSet<>(active.held)) {
            if (!desired.contains(key)) {
                unclaim(level, key, id);
                active.held.remove(key);
            }
        }
        writeManaged(worker, active);
        // Solo era necesaria para recuperar el primer registro tras reiniciar.
        active.restoredManaged.clear();
    }

    private static void claim(ServerLevel level, long key, UUID uuid, boolean restoredOwned) {
        Map<Long, Claim> worldClaims = CLAIMS.computeIfAbsent(level, ignored -> new HashMap<>());
        Claim claim = worldClaims.get(key);
        if (claim == null) {
            // No alterar los chunks que el administrador ya habia forzado.
            boolean alreadyForced = level.getForceLoadedChunks().contains(key);
            boolean managed = restoredOwned || !alreadyForced;
            claim = new Claim(managed);
            worldClaims.put(key, claim);
            if (!alreadyForced) {
                ChunkPos pos = new ChunkPos(key);
                level.setChunkForced(pos.x, pos.z, true);
            }
        } else if (restoredOwned) {
            // Un segundo clon puede restaurarse antes que el clon que habia
            // creado originalmente el forceload. Conservamos la propiedad.
            claim.managedByMod = true;
        }
        claim.holders.add(uuid);
    }

    private static void unclaim(ServerLevel level, long key, UUID uuid) {
        Map<Long, Claim> worldClaims = CLAIMS.get(level);
        if (worldClaims == null) return;
        Claim claim = worldClaims.get(key);
        if (claim == null) return;
        claim.holders.remove(uuid);
        if (!claim.holders.isEmpty()) return;

        worldClaims.remove(key);
        if (claim.managedByMod) {
            ChunkPos pos = new ChunkPos(key);
            level.setChunkForced(pos.x, pos.z, false);
        }
        if (worldClaims.isEmpty()) CLAIMS.remove(level);
    }

    /** Se llama al eliminar o descargar una entidad, excepto durante el apagado. */
    static void release(CloneWorker worker) {
        Active active = ACTIVE.remove(worker.uuid());
        if (active == null) return;
        for (long key : new ArrayList<>(active.held)) {
            unclaim(active.level, key, worker.uuid());
        }
        // El clon puede quedar descargado en vez de ser destruido: conservar su
        // estado solo si la descarga no fue una eliminacion real seria ambiguo;
        // al estar forzados los chunks, esa descarga no deberia ocurrir normalmente.
        CloneManager.setTag(worker.body(), TAG_MANAGED_CHUNKS, null);
    }

    /** Durante el cierre NO desforzar: los chunks y tags deben persistir. */
    static void clearInMemory() {
        ACTIVE.clear();
        CLAIMS.clear();
    }

    private static Set<Long> readManaged(CloneWorker worker) {
        Set<Long> result = new HashSet<>();
        String tag = CloneManager.getTag(worker.body(), TAG_MANAGED_CHUNKS);
        if (tag == null || tag.isBlank()) return result;
        for (String part : tag.split(";")) {
            try {
                result.add(Long.parseUnsignedLong(part, 16));
            } catch (NumberFormatException ignored) {
                // Ignorar fragmentos danados, sin afectar el resto.
            }
        }
        return result;
    }

    private static void writeManaged(CloneWorker worker, Active active) {
        Map<Long, Claim> claims = CLAIMS.get(active.level);
        if (claims == null) return;
        StringBuilder value = new StringBuilder();
        for (long key : active.held) {
            Claim claim = claims.get(key);
            if (claim == null || !claim.managedByMod) continue;
            if (!value.isEmpty()) value.append(';');
            value.append(Long.toUnsignedString(key, 16));
        }
        String updated = value.isEmpty() ? null : value.toString();
        // No reescribir NBT/tags 20 veces por segundo si nada ha cambiado.
        if (!java.util.Objects.equals(updated,
                CloneManager.getTag(worker.body(), TAG_MANAGED_CHUNKS))) {
            CloneManager.setTag(worker.body(), TAG_MANAGED_CHUNKS, updated);
        }
    }
}
