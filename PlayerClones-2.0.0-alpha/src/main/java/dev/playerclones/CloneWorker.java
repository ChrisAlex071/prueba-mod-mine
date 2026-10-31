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
    private enum State { SEARCH, TO_CHEST, TO_VILLAGER, DEPOSIT, PATROL, RETURN_HOME }
    private enum Walk { MOVING, ARRIVED, STUCK }

    private static final int CHEST_RADIUS = 16;
    private static final int CHEST_HEIGHT = 6;
    private static final int VILLAGER_RADIUS = 32;
    private static final int PATROL_VILLAGER_RADIUS = 6;
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
    private final List<BlockPos> route = new ArrayList<>();
    private int routeIndex;
    private int routeDirection = 1;
    private boolean completedLap;
    private int patrolScanCooldown;

    // Navegación opcional: el movimiento directo original sigue siendo el predeterminado.
    private Vec3 navigationTarget;
    private List<Vec3> navigationPath = List.of();
    private int navigationStep;
    private int navigationBlockedTicks;
    private int navigationRetryDelay;

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
    ChunkPos chunkPos() {
    return new ChunkPos(clone.getBlockX() >> 4, clone.getBlockZ() >> 4);
    }

    public List<BlockPos> route() { return List.copyOf(route); }
    public boolean hasRoute() { return !route.isEmpty(); }

    public int toggleWaypoint(BlockPos pos) {
        int existing = route.indexOf(pos);
        if (existing >= 0) {
            route.remove(existing);
            routeIndex = 0;
            resetPatrol();
            saveRoute();
            return -(existing + 1);
        }
        if (route.size() >= 64) return 0;
        route.add(pos.immutable());
        resetPatrol();
        saveRoute();
        return route.size();
    }

    private void resetPatrol() {
        state = State.SEARCH;
        routeIndex = 0;
        routeDirection = 1;
        completedLap = false;
        patrolScanCooldown = 0;
        resetNavigation();
        targetVillager = null;
        targetChest = null;
        ignoredVillagers.clear();
    }

    public boolean hasTask() { return give != null && receive != null; }

    // ---------------------------------------------------------------- tarea

    public void assign(Item give, Item receive) {
        this.give = give;
        this.receive = receive;
        resetPatrol();
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
        resetPatrol();
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
                + " | puntos: " + route.size()
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
        String savedChest = CloneManager.getTag(clone, "playerclones:source=");
        if (savedChest != null) {
            String[] xyz = savedChest.split(",");
            if (xyz.length == 3) try {
                sourceChest = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            } catch (NumberFormatException ignored) { }
        }
        String savedRoute = CloneManager.getTag(clone, CloneManager.TAG_ROUTE);
        if (savedRoute != null) {
            for (String entry : savedRoute.split(";")) {
                String[] xyz = entry.split(",");
                if (xyz.length != 3 || route.size() >= 64) continue;
                try {
                    route.add(new BlockPos(Integer.parseInt(xyz[0]),
                            Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
                } catch (NumberFormatException ignored) { }
            }
        }
    }

    private void saveState() {
        CloneManager.setTag(clone, "playerclones:source=", sourceChest == null ? null :
                sourceChest.getX() + "," + sourceChest.getY() + "," + sourceChest.getZ());
        CloneManager.setTag(clone, CloneManager.TAG_GIVE, give == null ? null : ItemLookup.fullName(give));
        CloneManager.setTag(clone, CloneManager.TAG_RECEIVE, receive == null ? null : ItemLookup.fullName(receive));
        CloneManager.setTag(clone, CloneManager.TAG_CARRY_GIVE, carriedGive > 0 ? String.valueOf(carriedGive) : null);
        CloneManager.setTag(clone, CloneManager.TAG_CARRY_RECEIVE,
                carriedReceive > 0 ? String.valueOf(carriedReceive) : null);
    }

    private void saveRoute() {
        StringBuilder out = new StringBuilder();
        for (BlockPos p : route) {
            if (!out.isEmpty()) out.append(';');
            out.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
        }
        CloneManager.setTag(clone, CloneManager.TAG_ROUTE, out.isEmpty() ? null : out.toString());
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
            if (hasRoute()) {
                patrolTick();
            } else if (cooldown > 0) {
                cooldown--;
            } else {
                switch (state) {
                    case SEARCH -> search();
                    case TO_CHEST -> toChest();
                    case TO_VILLAGER -> toVillager();
                    case DEPOSIT -> deposit();
                    case PATROL, RETURN_HOME -> state = State.SEARCH;
                }
            }
        }
        if (!movedThisTick) move(0, 0); // mantener fisica aunque este quieto
    }

    // ---------------------------------------------------------------- patrulla

    private void patrolTick() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        switch (state) {
            case SEARCH -> {
                if (completedLap) {
                    state = State.RETURN_HOME;
                } else if (carriedGive <= 0) {
                    // Solo tomar del cofre de origen una vez asociado.
                    BlockPos chest = sourceChest;
                    if (chest == null) {
                        for (BlockPos pos : nearbyChests()) {
                            if (count(containerAt(pos), give) > 0) { chest = pos; break; }
                        }
                    }
                    if (chest != null && count(containerAt(chest), give) > 0) {
                        targetChest = chest;
                        state = State.TO_CHEST;
                    } else {
                        cooldown = 100;
                    }
                } else {
                    routeIndex = 0;
                    routeDirection = 1;
                    ignoredVillagers.clear();
                    state = State.PATROL;
                }
            }
            case TO_CHEST -> toChest();
            case TO_VILLAGER -> toVillager();
            case DEPOSIT -> deposit();
            case PATROL -> patrol();
            case RETURN_HOME -> returnHome();
        }
    }

    private void patrol() {
        if (route.isEmpty()) { state = State.SEARCH; return; }
        if (carriedGive <= 0) { completedLap = true; state = State.RETURN_HOME; return; }
        if (--patrolScanCooldown <= 0) {
            patrolScanCooldown = 10;
            Villager v = findVillager(PATROL_VILLAGER_RADIUS);
            if (v != null) {
                targetVillager = v;
                state = State.TO_VILLAGER;
                return;
            }
        }
        BlockPos point = route.get(Math.min(routeIndex, route.size() - 1));
        switch (walkToNavigated(Vec3.atBottomCenterOf(point))) {
            case ARRIVED -> {
                if (route.size() == 1) {
                    completedLap = true;
                    state = State.RETURN_HOME;
                } else if (routeDirection > 0 && routeIndex == route.size() - 1) {
                    routeDirection = -1;
                    routeIndex--;
                } else if (routeDirection < 0 && routeIndex == 0) {
                    completedLap = true;
                    state = State.RETURN_HOME;
                } else {
                    routeIndex += routeDirection;
                }
            }
            case STUCK -> {
                // Sin pathfinding complejo: salta el punto bloqueado para no detenerse para siempre.
                if (routeDirection > 0 && routeIndex == route.size() - 1) {
                    routeDirection = -1;
                    routeIndex = Math.max(0, routeIndex - 1);
                } else if (routeDirection < 0 && routeIndex == 0) {
                    completedLap = true;
                    state = State.RETURN_HOME;
                } else {
                    routeIndex += routeDirection;
                }
            }
            default -> { }
        }
    }

    private void returnHome() {
        if (sourceChest == null) {
            // Tras reiniciar, el origen puede estar sin registrar. Recuperarlo al pasar cerca.
            for (BlockPos pos : nearbyChests()) {
                if (hasRoom(containerAt(pos))) { sourceChest = pos; break; }
            }
            if (sourceChest == null) { cooldown = 100; return; }
        }
        Walk walk = walkToNavigated(Vec3.atBottomCenterOf(sourceChest));
        if (walk == Walk.ARRIVED) {
            if (carriedReceive > 0) {
                targetChest = sourceChest;
                state = State.DEPOSIT;
            } else {
                completedLap = false;
                ignoredVillagers.clear();
                state = State.SEARCH;
            }
        } else if (walk == Walk.STUCK) {
            cooldown = 40;
        }
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
        switch (hasRoute() ? walkToNavigated(Vec3.atBottomCenterOf(targetChest))
                : walkTo(Vec3.atBottomCenterOf(targetChest))) {
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
            state = hasRoute() ? State.PATROL : State.SEARCH;
            return;
        }
        switch (hasRoute() ? walkToNavigated(targetVillager.position())
                : walkTo(targetVillager.position())) {
            case ARRIVED -> {
                int trades = trade(targetVillager);
                if (hasRoute() || trades == 0) ignoredVillagers.add(targetVillager.getUUID());
                targetVillager = null;
                state = hasRoute() ? State.PATROL : State.SEARCH;
                cooldown = 10;
            }
            case STUCK -> {
                ignoredVillagers.add(targetVillager.getUUID());
                targetVillager = null;
                state = hasRoute() ? State.PATROL : State.SEARCH;
            }
            default -> { }
        }
    }

    private void deposit() {
        if (targetChest == null || !hasRoom(containerAt(targetChest))) {
            targetChest = hasRoute() ? sourceChest : pickDepositChest();
            if (hasRoute() && !hasRoom(containerAt(targetChest))) targetChest = null;
            if (targetChest == null) {
                cooldown = 100; // no hay cofre con espacio
                return;
            }
        }
        switch (hasRoute() ? walkToNavigated(Vec3.atBottomCenterOf(targetChest))
                : walkTo(Vec3.atBottomCenterOf(targetChest))) {
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
                    if (hasRoute()) {
                        completedLap = false;
                        ignoredVillagers.clear();
                    }
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

    private Villager findVillager() { return findVillager(VILLAGER_RADIUS); }

    private Villager findVillager(int radius) {
        Villager best = null;
        double bestDist = Double.MAX_VALUE;
        for (Villager v : level().getEntitiesOfClass(Villager.class,
                clone.getBoundingBox().inflate(radius), e -> e.isAlive())) {
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
        if (c == null || item == null) return 0;
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

    private void resetNavigation() {
        navigationTarget = null;
        navigationPath = List.of();
        navigationStep = 0;
        navigationBlockedTicks = 0;
        navigationRetryDelay = 0;
    }

    /** Intenta movimiento normal; si se atasca, calcula un rodeo entre los puntos. */
    private Walk walkToNavigated(Vec3 target) {
        if (navigationTarget == null || navigationTarget.distanceToSqr(target) > 1.0) {
            resetNavigation();
            navigationTarget = target;
        }

        Vec3 current = clone.position();
        if (current.distanceToSqr(target) <= REACH * REACH
                && Math.abs(current.y - target.y) < 3) {
            resetNavigation();
            return Walk.ARRIVED;
        }

        if (!navigationPath.isEmpty()) {
            while (navigationStep < navigationPath.size()) {
                Vec3 step = navigationPath.get(navigationStep);
                if (clone.position().distanceToSqr(step) < .65 * .65) {
                    navigationStep++;
                    continue;
                }
                Walk result = walkToRaw(step, .55);
                if (result == Walk.STUCK) {
                    navigationPath = List.of();
                    navigationStep = 0;
                    navigationBlockedTicks = 12;
                    navigationRetryDelay = 0;
                }
                return Walk.MOVING;
            }
            navigationPath = List.of();
            navigationStep = 0;
        }

        Vec3 before = clone.position();
        Walk direct = walkToRaw(target, REACH);
        if (direct == Walk.ARRIVED) {
            resetNavigation();
            return direct;
        }
        if (direct == Walk.STUCK ||
                Math.pow(clone.getX() - before.x, 2) + Math.pow(clone.getZ() - before.z, 2) < .0004) {
            navigationBlockedTicks++;
        } else {
            navigationBlockedTicks = 0;
        }
        if (navigationRetryDelay > 0) navigationRetryDelay--;
        if (navigationBlockedTicks >= 12 && navigationRetryDelay == 0) {
            navigationPath = ClonePathfinder.find(level(), clone.position(), target);
            navigationStep = 0;
            navigationBlockedTicks = 0;
            navigationRetryDelay = 40;
            if (navigationPath.isEmpty() && direct == Walk.STUCK) return Walk.STUCK;
        }
        return Walk.MOVING;
    }

    private Walk walkTo(Vec3 target) {
        return walkToRaw(target, REACH);
    }

    private Walk walkToRaw(Vec3 target, double reach) {
        Vec3 p = clone.position();
        double dx = target.x - p.x;
        double dz = target.z - p.z;
        double distSq = dx * dx + dz * dz;

        if (distSq <= reach * reach && Math.abs(target.y - p.y) < (reach < 1 ? 1.3 : 3)) {
            stuck = 0;
            return Walk.ARRIVED;
        }

        double dist = Math.sqrt(distSq);
        if (dist < 0.00001) return Walk.STUCK;
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
