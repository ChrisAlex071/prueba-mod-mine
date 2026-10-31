package dev.playerclones;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Un clon con su tarea. Tarea "trade <dar> <recibir>":
 *   1. toma <dar> de cofres cercanos,
 *   2. lo cambia por <recibir> con aldeanos cercanos,
 *   3. guarda <recibir> en un cofre (prefiere el cofre de origen),
 *   4. repite; si no hay trabajo, espera y reintenta.
 * Corre en el servidor: CloneChunkLoader mantiene cargado el area necesaria.
 */
public final class CloneWorker {
    private enum State { SEARCH, TO_CHEST, TO_VILLAGER, DEPOSIT }
    private enum Walk { MOVING, ARRIVED, STUCK }

    private static final int CHEST_RADIUS = 16;
    private static final int CHEST_HEIGHT = 6;
    private static final int VILLAGER_RADIUS = 32;
    private static final double SPEED = 0.2;   // bloques/tick (~caminar)
    private static final double REACH = 2.5;   // distancia para interactuar
    private static final int STUCK_LIMIT = 60; // ticks sin avanzar antes de descartar el objetivo

    private final int id;
    private final UUID owner;
    private final Mannequin clone;

    // Tarea (persistida en tags)
    private Item give;
    private Item receive;
    private int carriedGive;
    private int carriedReceive;

    // Estado de ejecucion (no persistido)
    private State state = State.SEARCH;
    private BlockPos targetChest;
    private BlockPos sourceChest;
    private Villager targetVillager;
    private final Set<BlockPos> ignoredChests = new HashSet<>();
    private final Set<UUID> ignoredVillagers = new HashSet<>();
    private int cooldown;
    private int stuck;
    private double vy;
    private boolean movedThisTick;
    private Vec3 lastPos;

    CloneWorker(int id, UUID owner, Mannequin clone) {
        this.id = id;
        this.owner = owner;
        this.clone = clone;
        this.lastPos = clone.position();
        this.clone.setNoGravity(true); // la gravedad la manejamos en move()
        loadState();
    }

    public int id() { return id; }
    public UUID owner() { return owner; }
    public UUID uuid() { return clone.getUUID(); }
    public void discard() { clone.discard(); }
    public boolean isRemoved() { return clone.isRemoved(); }
    Mannequin body() { return clone; }
    ServerLevel serverLevel() { return (ServerLevel) clone.level(); }
    ChunkPos chunkPos() { return new ChunkPos(clone.blockPosition()); }
    public boolean hasTask() { return give != null && receive != null; }

    // ---------------------------------------------------------------- tarea

    public void assign(Item give, Item receive) {
        this.give = give;
        this.receive = receive;
        this.state = State.SEARCH;
        this.targetChest = null;
        this.targetVillager = null;
        this.cooldown = 0;
        ignoredChests.clear();
        ignoredVillagers.clear();
        saveState();
        CloneChunkLoader.refresh(this);
    }

    /** Detiene la tarea. Lo que lleva encima se conserva por si se reasigna. */
    public void clearTask() {
        give = null;
        receive = null;
        targetChest = null;
        targetVillager = null;
        clone.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        saveState();
        CloneChunkLoader.refresh(this);
    }

    public String describeTask() {
        return hasTask()
                ? "trade " + ItemLookup.shortName(give) + " -> " + ItemLookup.shortName(receive)
                : "sin tarea";
    }

    public String describeFull() {
        Vec3 p = clone.position();
        String carry = "";
        if (give != null && carriedGive > 0) carry += carriedGive + "x " + ItemLookup.shortName(give) + " ";
        if (receive != null && carriedReceive > 0) carry += carriedReceive + "x " + ItemLookup.shortName(receive);
        return "tarea: " + describeTask()
                + " | estado: " + (hasTask() ? state.name() : "IDLE")
                + " | lleva: " + (carry.isBlank() ? "nada" : carry.trim())
                + String.format(" | pos: %.0f %.0f %.0f", p.x, p.y, p.z);
    }

    public void dropCarried() {
        if (give != null) dropItems(give, carriedGive);
        if (receive != null) dropItems(receive, carriedReceive);
        carriedGive = 0;
        carriedReceive = 0;
        saveState();
    }

    private void dropItems(Item item, int amount) {
        ServerLevel lvl = level();
        while (amount > 0) {
            int n = Math.min(amount, new ItemStack(item).getMaxStackSize());
            lvl.addFreshEntity(new ItemEntity(lvl, clone.getX(), clone.getY(), clone.getZ(), new ItemStack(item, n)));
            amount -= n;
        }
    }

    // ----------------------------------------------------------- persistencia

    private void loadState() {
        give = ItemLookup.byName(CloneManager.getTag(clone, CloneManager.TAG_GIVE));
        receive = ItemLookup.byName(CloneManager.getTag(clone, CloneManager.TAG_RECEIVE));
        carriedGive = intTag(CloneManager.TAG_CARRY_GIVE);
        carriedReceive = intTag(CloneManager.TAG_CARRY_RECEIVE);
    }

    private void saveState() {
        CloneManager.setTag(clone, CloneManager.TAG_GIVE, give == null ? null : ItemLookup.fullName(give));
        CloneManager.setTag(clone, CloneManager.TAG_RECEIVE, receive == null ? null : ItemLookup.fullName(receive));
        CloneManager.setTag(clone, CloneManager.TAG_CARRY_GIVE, carriedGive > 0 ? String.valueOf(carriedGive) : null);
        CloneManager.setTag(clone, CloneManager.TAG_CARRY_RECEIVE,
                carriedReceive > 0 ? String.valueOf(carriedReceive) : null);
    }

    private int intTag(String prefix) {
        String v = CloneManager.getTag(clone, prefix);
        if (v == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(v));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // ------------------------------------------------------------------- tick

    void tick() {
        if (clone.isRemoved()) return;
        movedThisTick = false;
        clone.setDeltaMovement(Vec3.ZERO);

        if (hasTask()) {
            if (cooldown > 0) {
                cooldown--;
            } else {
                switch (state) {
                    case SEARCH -> search();
                    case TO_CHEST -> toChest();
                    case TO_VILLAGER -> toVillager();
                    case DEPOSIT -> deposit();
                }
            }
        }
        if (!movedThisTick) move(0, 0); // mantener fisica aunque este quieto
    }

    // ---------------------------------------------------------------- estados

    private void search() {
        if (carriedReceive > 0) {
            targetChest = null;
            state = State.DEPOSIT;
            return;
        }
        if (carriedGive > 0) {
            Villager v = findVillager();
            if (v != null) {
                targetVillager = v;
                state = State.TO_VILLAGER;
                return;
            }
        }
        for (BlockPos pos : nearbyChests()) {
            if (ignoredChests.contains(pos)) continue;
            Container c = containerAt(pos);
            if (c != null && count(c, give) > 0) {
                targetChest = pos;
                state = State.TO_CHEST;
                return;
            }
        }
        // Nada que hacer: esperar 5 s, olvidar descartes y reintentar
        ignoredChests.clear();
        ignoredVillagers.clear();
        cooldown = 100;
    }

    private void toChest() {
        Container c = containerAt(targetChest);
        if (c == null || count(c, give) == 0) {
            state = State.SEARCH;
            return;
        }
        switch (walkTo(Vec3.atBottomCenterOf(targetChest))) {
            case ARRIVED -> {
                int taken = takeAll(c, give);
                carriedGive += taken;
                sourceChest = targetChest;
                clone.swing(InteractionHand.MAIN_HAND);
                clone.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(give));
                saveState();
                tell("Tome " + taken + "x " + ItemLookup.shortName(give) + " de un cofre.");
                state = State.SEARCH;
                cooldown = 10;
            }
            case STUCK -> {
                ignoredChests.add(targetChest);
                state = State.SEARCH;
            }
            default -> { }
        }
    }

    private void toVillager() {
        if (targetVillager == null || !targetVillager.isAlive()) {
            targetVillager = null;
            state = State.SEARCH;
            return;
        }
        switch (walkTo(targetVillager.position())) {
            case ARRIVED -> {
                int trades = trade(targetVillager);
                if (trades == 0) ignoredVillagers.add(targetVillager.getUUID());
                targetVillager = null;
                state = State.SEARCH;
                cooldown = 10;
            }
            case STUCK -> {
                ignoredVillagers.add(targetVillager.getUUID());
                targetVillager = null;
                state = State.SEARCH;
            }
            default -> { }
        }
    }

    private void deposit() {
        if (targetChest == null || !hasRoom(containerAt(targetChest))) {
            targetChest = pickDepositChest();
            if (targetChest == null) {
                cooldown = 100; // no hay cofre con espacio
                return;
            }
        }
        switch (walkTo(Vec3.atBottomCenterOf(targetChest))) {
            case ARRIVED -> {
                Container c = containerAt(targetChest);
                int before = carriedReceive;
                carriedReceive = insert(c, receive, carriedReceive);
                clone.swing(InteractionHand.MAIN_HAND);
                saveState();
                tell("Guarde " + (before - carriedReceive) + "x " + ItemLookup.shortName(receive) + " en un cofre.");
                if (carriedReceive == 0) {
                    clone.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    state = State.SEARCH;
                    cooldown = 10;
                } else {
                    targetChest = null; // se lleno, buscar otro
                }
            }
            case STUCK -> {
                ignoredChests.add(targetChest);
                targetChest = null;
            }
            default -> { }
        }
    }

    // ----------------------------------------------------------------- trades

    private boolean isWantedOffer(MerchantOffer o) {
        return o.getCostA().is(give) && o.getCostB().isEmpty() && o.getResult().is(receive);
    }

    private Villager findVillager() {
        Villager best = null;
        double bestDist = Double.MAX_VALUE;
        for (Villager v : level().getEntitiesOfClass(Villager.class,
                clone.getBoundingBox().inflate(VILLAGER_RADIUS), e -> e.isAlive())) {
            if (ignoredVillagers.contains(v.getUUID())) continue;
            boolean usable = false;
            for (MerchantOffer o : v.getOffers()) {
                if (isWantedOffer(o) && !o.isOutOfStock() && carriedGive >= o.getCostA().getCount()) {
                    usable = true;
                    break;
                }
            }
            if (!usable) continue;
            double d = v.distanceToSqr(clone);
            if (d < bestDist) {
                bestDist = d;
                best = v;
            }
        }
        return best;
    }

    private int trade(Villager v) {
        int trades = 0;
        int spent = 0;
        int gained = 0;
        for (MerchantOffer offer : v.getOffers()) {
            if (!isWantedOffer(offer)) continue;
            while (!offer.isOutOfStock() && carriedGive >= Math.max(1, offer.getCostA().getCount())) {
                int cost = Math.max(1, offer.getCostA().getCount());
                int result = offer.getResult().getCount();
                carriedGive -= cost;
                carriedReceive += result;
                spent += cost;
                gained += result;
                v.notifyTrade(offer); // gasta un uso, da XP al aldeano, etc.
                trades++;
            }
        }
        clone.swing(InteractionHand.MAIN_HAND);
        if (trades > 0) {
            clone.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(receive));
            saveState();
            tell("Cambie " + spent + "x " + ItemLookup.shortName(give)
                    + " por " + gained + "x " + ItemLookup.shortName(receive) + ".");
        }
        return trades;
    }

    // ----------------------------------------------------------------- cofres

    private List<BlockPos> nearbyChests() {
        ServerLevel lvl = level();
        BlockPos c = clone.blockPosition();
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(
                c.offset(-CHEST_RADIUS, -CHEST_HEIGHT, -CHEST_RADIUS),
                c.offset(CHEST_RADIUS, CHEST_HEIGHT, CHEST_RADIUS))) {
            if (lvl.hasChunkAt(p) && lvl.getBlockEntity(p) instanceof ChestBlockEntity) {
                out.add(p.immutable());
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(c)));
        return out;
    }

    private Container containerAt(BlockPos pos) {
        ServerLevel lvl = level();
        if (pos == null || !lvl.hasChunkAt(pos)) return null;
        return lvl.getBlockEntity(pos) instanceof ChestBlockEntity chest ? chest : null;
    }

    private BlockPos pickDepositChest() {
        if (sourceChest != null && !ignoredChests.contains(sourceChest) && hasRoom(containerAt(sourceChest))) {
            return sourceChest; // preferimos devolver al cofre de origen
        }
        for (BlockPos p : nearbyChests()) {
            if (!ignoredChests.contains(p) && hasRoom(containerAt(p))) return p;
        }
        return null;
    }

    private static int count(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static int takeAll(Container c, Item item) {
        int taken = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.is(item)) {
                taken += s.getCount();
                c.setItem(i, ItemStack.EMPTY);
            }
        }
        if (taken > 0) c.setChanged();
        return taken;
    }

    private boolean hasRoom(Container c) {
        if (c == null) return false;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || (s.is(receive) && s.getCount() < s.getMaxStackSize())) return true;
        }
        return false;
    }

    /** Inserta en el contenedor y devuelve lo que NO cupo. */
    private static int insert(Container c, Item item, int amount) {
        if (c == null) return amount;
        int max = new ItemStack(item).getMaxStackSize();
        for (int i = 0; i < c.getContainerSize() && amount > 0; i++) {
            ItemStack s = c.getItem(i);
            if (s.is(item) && s.getCount() < s.getMaxStackSize()) {
                int add = Math.min(amount, s.getMaxStackSize() - s.getCount());
                s.grow(add);
                amount -= add;
            }
        }
        for (int i = 0; i < c.getContainerSize() && amount > 0; i++) {
            if (c.getItem(i).isEmpty()) {
                int add = Math.min(amount, max);
                c.setItem(i, new ItemStack(item, add));
                amount -= add;
            }
        }
        c.setChanged();
        return amount;
    }

    // ------------------------------------------------------------- movimiento

    private Walk walkTo(Vec3 target) {
        Vec3 p = clone.position();
        double dx = target.x - p.x;
        double dz = target.z - p.z;
        double distSq = dx * dx + dz * dz;

        if (distSq <= REACH * REACH && Math.abs(target.y - p.y) < 3) {
            stuck = 0;
            return Walk.ARRIVED;
        }

        double dist = Math.sqrt(distSq);
        face(dx, dz);
        move(dx / dist * SPEED, dz / dist * SPEED);

        Vec3 now = clone.position();
        if (now.distanceToSqr(lastPos) < 0.0004) stuck++; else stuck = 0;
        lastPos = now;

        if (stuck > STUCK_LIMIT) {
            stuck = 0;
            return Walk.STUCK;
        }
        return Walk.MOVING;
    }

    private void face(double dx, double dz) {
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        clone.setYRot(yaw);
        clone.setYHeadRot(yaw);
        clone.setYBodyRot(yaw);
    }

    /** Movimiento basico con gravedad y salto de 1 bloque (no es pathfinding real). */
    private void move(double mx, double mz) {
        movedThisTick = true;
        if (clone.onGround()) {
            boolean blocked = clone.horizontalCollision && (mx != 0 || mz != 0);
            vy = blocked ? 0.42 : -0.05;
        } else {
            vy = Math.max(vy - 0.08, -1.0);
        }
        clone.move(MoverType.SELF, new Vec3(mx, vy, mz));
    }

    // ------------------------------------------------------------------ varios

    private ServerLevel level() {
        return (ServerLevel) clone.level();
    }

    private void tell(String msg) {
        ServerPlayer p = level().getServer().getPlayerList().getPlayer(owner);
        if (p != null) p.sendSystemMessage(Component.literal("[Clon #" + id + "] " + msg));
    }
}
