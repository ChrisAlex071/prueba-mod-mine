package dev.playerclones;

import java.util.ArrayList;
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

    /**
     * Crea los clones con el comando /summon (asi no dependemos de nombres internos de la API).
     * La entidad se registra sola en PlayerClones (ENTITY_LOAD) gracias a los tags.
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

            CloneWorker worker = CLONES.get(id);
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

    /** Lee los tags de la entidad; el metodo se llama distinto segun la version, asi que probamos ambos. */
    @SuppressWarnings("unchecked")
    private static Set<String> tagsOf(Entity e) {
        for (String name : new String[] {"entityTags", "getTags"}) {
            try {
                return (Set<String>) Entity.class.getMethod(name).invoke(e);
            } catch (ReflectiveOperationException | ClassCastException ignored) {
                // probar el siguiente nombre
            }
        }
        return Set.of();
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
