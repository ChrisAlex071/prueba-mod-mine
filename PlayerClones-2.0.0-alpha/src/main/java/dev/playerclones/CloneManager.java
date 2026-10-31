package dev.playerclones;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.AABB;

/** Registro de clones. Cada clon mantiene cargada su zona de trabajo. */
public final class CloneManager {
    public static final int MAX_PER_PLAYER = 10;

    // Tags guardados en la entidad (persisten en el NBT del mundo)
    static final String TAG_ID = "playerclones:id=";
    static final String TAG_OWNER = "playerclones:owner=";
    static final String TAG_GIVE = "playerclones:give=";
    static final String TAG_RECEIVE = "playerclones:receive=";
    static final String TAG_CARRY_GIVE = "playerclones:carry_give=";
    static final String TAG_CARRY_RECEIVE = "playerclones:carry_receive=";
    static final String TAG_ROUTE = "playerclones:route=";

    private static final Map<Integer, CloneWorker> CLONES = new LinkedHashMap<>();
    private static int nextId = 1;

    private CloneManager() {}

    // ---------------------------------------------------------------- consulta

    public static CloneWorker get(int id) {
        return CLONES.get(id);
    }

    public static List<CloneWorker> all() {
        return new ArrayList<>(CLONES.values());
    }

    public static List<CloneWorker> ownedBy(UUID owner) {
        List<CloneWorker> out = new ArrayList<>();
        for (CloneWorker w : CLONES.values()) {
            if (w.owner().equals(owner)) out.add(w);
        }
        return out;
    }

    public static int countOwned(UUID owner) {
        return ownedBy(owner).size();
    }

    private static boolean isRegistered(Entity e) {
        for (CloneWorker w : CLONES.values()) {
            if (w.uuid().equals(e.getUUID())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ ciclo

    /**
     * Crea los clones con /summon (asi no dependemos de nombres internos de la API) y luego
     * localiza la entidad recien creada para registrarla directamente.
     */
    public static List<CloneWorker> spawn(ServerPlayer owner, int amount) {
        ServerLevel level = (ServerLevel) owner.level();
        MinecraftServer server = level.getServer();
        List<CloneWorker> out = new ArrayList<>();

        String ownerName = owner.getName().getString().replace("\\", "").replace("\"", "");
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(level)
                .withPosition(owner.position())
                .withSuppressedOutput();

        for (int i = 0; i < amount; i++) {
            int id = nextId++;
            double angle = (2 * Math.PI * i) / Math.max(1, amount);
            double x = owner.getX() + Math.cos(angle) * 1.5;
            double y = owner.getY();
            double z = owner.getZ() + Math.sin(angle) * 1.5;

            String nbt = String.format(Locale.ROOT,
                    "{profile:{name:\"%s\"},CustomName:{text:\"%s (Clon #%d)\"},CustomNameVisible:1b,"
                            + "hide_description:1b,Rotation:[%.1ff,0.0f],Tags:[\"%s%d\",\"%s%s\"]}",
                    ownerName, ownerName, id, owner.getYRot(),
                    TAG_ID, id, TAG_OWNER, owner.getUUID());
            String command = String.format(Locale.ROOT,
                    "summon minecraft:mannequin %.3f %.3f %.3f %s", x, y, z, nbt);

            server.getCommands().performPrefixedCommand(source, command);

            // Puede que el evento ENTITY_LOAD ya lo haya registrado...
            CloneWorker worker = CLONES.get(id);
            if (worker == null) {
                // ...si no, buscamos la entidad recien creada en el punto exacto y la adoptamos.
                AABB box = new AABB(x - 0.3, y - 0.5, z - 0.3, x + 0.3, y + 1.0, z + 0.3);
                for (Mannequin m : level.getEntitiesOfClass(Mannequin.class, box)) {
                    if (isRegistered(m)) continue;
                    worker = adopt(m, id, owner.getUUID());
                    break;
                }
            }
            if (worker != null) out.add(worker);
        }
        return out;
    }

    private static CloneWorker adopt(Mannequin body, int id, UUID owner) {
        setTag(body, TAG_ID, String.valueOf(id));
        setTag(body, TAG_OWNER, owner.toString());
        nextId = Math.max(nextId, id + 1);
        CloneWorker worker = new CloneWorker(id, owner, body);
        CLONES.put(id, worker);
        CloneChunkLoader.refresh(worker);
        return worker;
    }

    public static void remove(CloneWorker worker) {
        worker.dropCarried();
        CloneChunkLoader.release(worker);
        CLONES.remove(worker.id());
        worker.discard();
    }

    /** Se llama al cargar la entidad (p. ej. tras reiniciar el mundo). Idempotente. */
    static CloneWorker register(Mannequin body) {
        for (CloneWorker w : CLONES.values()) {
            if (w.uuid().equals(body.getUUID())) return w;
        }
        String idTag = getTag(body, TAG_ID);
        String ownerTag = getTag(body, TAG_OWNER);
        if (idTag == null || ownerTag == null) return null; // no es un clon nuestro

        int id;
        UUID owner;
        try {
            id = Integer.parseInt(idTag);
            owner = UUID.fromString(ownerTag);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (CLONES.containsKey(id)) { // ID repetido tras reinicios: reasignar
            id = nextId++;
            setTag(body, TAG_ID, String.valueOf(id));
        }
        nextId = Math.max(nextId, id + 1);

        CloneWorker worker = new CloneWorker(id, owner, body);
        CLONES.put(id, worker);
        CloneChunkLoader.refresh(worker);
        return worker;
    }

    static void unregister(Entity entity, boolean shuttingDown) {
        for (CloneWorker w : new ArrayList<>(CLONES.values())) {
            if (w.uuid().equals(entity.getUUID())) {
                if (!shuttingDown) CloneChunkLoader.release(w);
                CLONES.remove(w.id());
                break;
            }
        }
    }

    static void reset() {
        CLONES.clear();
        CloneChunkLoader.clearInMemory();
        nextId = 1;
    }

    public static void tick() {
        for (CloneWorker w : new ArrayList<>(CLONES.values())) {
            if (w.isRemoved()) {
                CloneChunkLoader.release(w);
                CLONES.remove(w.id());
            } else {
                CloneChunkLoader.refresh(w);
                w.tick();
                CloneChunkLoader.refresh(w);
            }
        }
    }

    // ------------------------------------------------------------- tags helper

    private static Method tagsMethod;
    private static boolean tagsProbed;

    /**
     * El metodo que devuelve los tags de Entity cambio de nombre en 26.x. En vez de adivinarlo,
     * ponemos un tag de prueba y buscamos cual metodo sin argumentos que devuelve un Set lo contiene.
     */
    private static void probeTagsMethod(Entity e) {
        tagsProbed = true;
        String probe = "playerclones:probe";
        e.addTag(probe);
        for (Method m : Entity.class.getDeclaredMethods()) {
            if (m.getParameterCount() != 0 || !Set.class.isAssignableFrom(m.getReturnType())) continue;
            try {
                m.setAccessible(true);
                Object r = m.invoke(e);
                if (r instanceof Set<?> s && s.contains(probe)) {
                    tagsMethod = m;
                    break;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // probar el siguiente
            }
        }
        e.removeTag(probe);
        if (tagsMethod == null) {
            System.err.println("[PlayerClones] No se encontro el metodo de tags de Entity; "
                    + "los clones no se recuperaran tras reiniciar.");
        }
    }

    private static Set<String> tagsOf(Entity e) {
        if (!tagsProbed) probeTagsMethod(e);
        Set<String> out = new HashSet<>();
        if (tagsMethod == null) return out;
        try {
            Object r = tagsMethod.invoke(e);
            if (r instanceof Set<?> s) {
                for (Object o : s) {
                    if (o instanceof String str) out.add(str);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // devolver lo que haya
        }
        return out;
    }

    static String getTag(Entity e, String prefix) {
        for (String t : tagsOf(e)) {
            if (t.startsWith(prefix)) return t.substring(prefix.length());
        }
        return null;
    }

    static void setTag(Entity e, String prefix, String value) {
        for (String t : new ArrayList<>(tagsOf(e))) {
            if (t.startsWith(prefix)) e.removeTag(t);
        }
        if (value != null) e.addTag(prefix + value);
    }
}
