package dev.playerclones;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;

/** Registro de clones cargados. Los clones en chunks descargados quedan pausados. */
public final class CloneManager {
    public static final int MAX_PER_PLAYER = 10;

    // Tags guardados en la entidad (persisten en el NBT del mundo)
    static final String TAG_ID = "playerclones:id=";
    static final String TAG_OWNER = "playerclones:owner=";
    static final String TAG_GIVE = "playerclones:give=";
    static final String TAG_RECEIVE = "playerclones:receive=";
    static final String TAG_CARRY_GIVE = "playerclones:carry_give=";
    static final String TAG_CARRY_RECEIVE = "playerclones:carry_receive=";

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

    // ------------------------------------------------------------------ ciclo

    public static List<CloneWorker> spawn(ServerPlayer owner, int amount) {
        ServerLevel level = (ServerLevel) owner.level();
        List<CloneWorker> out = new ArrayList<>();

        for (int i = 0; i < amount; i++) {
            Mannequin body = EntityType.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
            if (body == null) continue;

            int id = nextId++;
            double angle = (2 * Math.PI * i) / Math.max(1, amount);
            body.setPos(owner.getX() + Math.cos(angle) * 1.5, owner.getY(), owner.getZ() + Math.sin(angle) * 1.5);
            body.setYRot(owner.getYRot());

            // Misma skin que el jugador
            body.setProfile(ResolvableProfile.createResolved(owner.getGameProfile()));
            body.setCustomName(Component.literal(owner.getName().getString() + " (Clon #" + id + ")"));
            body.setCustomNameVisible(true);

            setTag(body, TAG_ID, String.valueOf(id));
            setTag(body, TAG_OWNER, owner.getUUID().toString());

            level.addFreshEntity(body);
            CloneWorker worker = register(body);
            if (worker != null) out.add(worker);
        }
        return out;
    }

    public static void remove(CloneWorker worker) {
        worker.dropCarried();
        CLONES.remove(worker.id());
        worker.discard();
    }

    /** Se llama al cargar la entidad (o al crearla). Idempotente. */
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
        return worker;
    }

    static void unregister(Entity entity) {
        CLONES.values().removeIf(w -> w.uuid().equals(entity.getUUID()));
    }

    public static void tick() {
        for (CloneWorker w : new ArrayList<>(CLONES.values())) {
            w.tick();
        }
    }

    // ------------------------------------------------------------- tags helper

    static String getTag(Entity e, String prefix) {
        for (String t : e.getTags()) {
            if (t.startsWith(prefix)) return t.substring(prefix.length());
        }
        return null;
    }

    static void setTag(Entity e, String prefix, String value) {
        for (String t : new ArrayList<>(e.getTags())) {
            if (t.startsWith(prefix)) e.removeTag(t);
        }
        if (value != null) e.addTag(prefix + value);
    }
}
