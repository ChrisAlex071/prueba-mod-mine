package dev.playerclones;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final int CHEST_HEIGHT = 15; // altura de busqueda de cofres (arriba y abajo)
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
    private boolean navigationPlanned;
    private int navigationFailures;
    private final Set<BlockPos> openedDoors = new HashSet<>();

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

    public boolean hasTask() { return hasTradeTask() || isSorting() || isHarvesting(); }
    private boolean hasTradeTask() { return give != null && receive != null; }
    private boolean isSorting() { return sortCenter != null; }
    private boolean isHarvesting() { return harvestCenter != null; }

    // ---------------------------------------------------------------- tarea

    public void assign(Item give, Item receive) {
        this.give = give;
        this.receive = receive;
        this.sortCenter = null;
        this.sortMove = null;
        this.harvestCenter = null;
        dropCargo(); // si venia de cosechar, suelta lo que llevaba
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
        sortCenter = null;
        sortMove = null;
        harvestCenter = null;
        harvestTarget = null;
        targetChest = null;
        targetVillager = null;
        resetPatrol();
        clone.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        saveState();
        CloneChunkLoader.refresh(this);
    }

    public String describeTask() {
        if (isSorting()) return "ordenar cofres (radio " + sortRadius + ")";
        if (isHarvesting()) return "cosechar (radio " + harvestRadius + ")";
        return hasTradeTask()
                ? "trade " + ItemLookup.shortName(give) + " -> " + ItemLookup.shortName(receive)
                : "sin tarea";
    }

    public String describeFull() {
        Vec3 p = clone.position();
        if (isSorting()) {
            return "tarea: ordenar cofres (centro " + sortCenter.getX() + " " + sortCenter.getY() + " "
                    + sortCenter.getZ() + ", radio " + sortRadius + ")"
                    + " | estado: " + sortState.name()
                    + " | items movidos: " + sortMovedTotal
                    + String.format(" | pos: %.0f %.0f %.0f", p.x, p.y, p.z);
        }
        if (isHarvesting()) {
            StringBuilder lleva = new StringBuilder();
            for (Map.Entry<Item, Integer> e : cargo.entrySet()) {
                if (!lleva.isEmpty()) lleva.append(", ");
                lleva.append(e.getValue()).append("x ").append(ItemLookup.shortName(e.getKey()));
            }
            return "tarea: cosechar (centro " + harvestCenter.getX() + " " + harvestCenter.getY() + " "
                    + harvestCenter.getZ() + ", radio " + harvestRadius + ")"
                    + " | estado: " + harvestState.name()
                    + " | cultivos pendientes: " + crops.size()
                    + " | cosechados en esta ronda: " + harvestedRound
                    + " | lleva: " + (lleva.isEmpty() ? "nada" : lleva)
                    + String.format(" | pos: %.0f %.0f %.0f", p.x, p.y, p.z);
        }
        String carry = "";
        if (give != null && carriedGive > 0) carry += carriedGive + "x " + ItemLookup.shortName(give) + " ";
        if (receive != null && carriedReceive > 0) carry += carriedReceive + "x " + ItemLookup.shortName(receive);
        return "tarea: " + describeTask()
                + " | puntos: " + route.size()
                + " | estado: " + (hasTradeTask() ? state.name() : "IDLE")
                + " | lleva: " + (carry.isBlank() ? "nada" : carry.trim())
                + String.format(" | pos: %.0f %.0f %.0f", p.x, p.y, p.z);
    }

    public void dropCarried() {
        if (give != null) dropItems(give, carriedGive);
        if (receive != null) dropItems(receive, carriedReceive);
        carriedGive = 0;
        carriedReceive = 0;
        dropCargo();
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
        String savedHarvest = CloneManager.getTag(clone, "playerclones:harvest=");
        if (savedHarvest != null) {
            String[] v = savedHarvest.split(",");
            if (v.length == 4) try {
                harvestCenter = new BlockPos(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]));
                harvestRadius = Math.max(8, Math.min(100, Integer.parseInt(v[3])));
            } catch (NumberFormatException ignored) { }
        }
        loadCargo();
        String savedSort = CloneManager.getTag(clone, "playerclones:sort=");
        if (savedSort != null) {
            String[] v = savedSort.split(",");
            if (v.length == 4) try {
                sortCenter = new BlockPos(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]));
                sortRadius = Math.max(4, Math.min(24, Integer.parseInt(v[3])));
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
        CloneManager.setTag(clone, "playerclones:harvest=", harvestCenter == null ? null :
                harvestCenter.getX() + "," + harvestCenter.getY() + "," + harvestCenter.getZ() + "," + harvestRadius);
        CloneManager.setTag(clone, "playerclones:sort=", sortCenter == null ? null :
                sortCenter.getX() + "," + sortCenter.getY() + "," + sortCenter.getZ() + "," + sortRadius);
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

        if (isSorting()) {
            sortTick();
        } else if (isHarvesting()) {
            harvestTick();
        } else if (hasTradeTask()) {
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
        closeDoorsBehind();
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
        switch (walkToNavigated(Vec3.atBottomCenterOf(targetChest))) {
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
        switch (walkToNavigated(targetVillager.position())) {
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
        switch (walkToNavigated(Vec3.atBottomCenterOf(targetChest))) {
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
            if (v.getTradingPlayer() != null) continue; // un jugador esta comerciando con el
            boolean usable = false;
            applyOwnerPrices(v); // con el descuento del dueño, el hierro necesario puede ser menor
            try {
                for (MerchantOffer o : v.getOffers()) {
                    if (isWantedOffer(o) && !o.isOutOfStock() && carriedGive >= o.getCostA().getCount()) {
                        usable = true;
                        break;
                    }
                }
            } finally {
                clearSpecialPrices(v);
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
        if (v.getTradingPlayer() != null) return 0; // un jugador esta comerciando con el
        int trades = 0;
        int spent = 0;
        int gained = 0;
        int reputation = applyOwnerPrices(v);
        try {
            for (MerchantOffer offer : v.getOffers()) {
                if (!isWantedOffer(offer)) continue;
                while (!offer.isOutOfStock() && carriedGive >= Math.max(1, offer.getCostA().getCount())) {
                    int cost = Math.max(1, offer.getCostA().getCount()); // ya incluye el descuento del dueño
                    int result = offer.getResult().getCount();
                    carriedGive -= cost;
                    carriedReceive += result;
                    spent += cost;
                    gained += result;
                    v.notifyTrade(offer); // gasta un uso, da XP al aldeano, etc.
                    trades++;
                }
            }
        } finally {
            clearSpecialPrices(v);
        }
        clone.swing(InteractionHand.MAIN_HAND);
        if (trades > 0) {
            clone.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(receive));
            saveState();
            tell("Cambie " + spent + "x " + ItemLookup.shortName(give)
                    + " por " + gained + "x " + ItemLookup.shortName(receive)
                    + (reputation > 0 ? " (con tu descuento de reputacion)." : "."));
        }
        return trades;
    }

    /**
     * Aplica a las ofertas del aldeano el descuento que le corresponde al DUEÑO del clon
     * (p. ej. por haberlo curado de zombi), con la misma formula de vanilla. Se guarda por UUID
     * en la memoria del aldeano, asi que funciona aunque el dueño este desconectado.
     * Devuelve la reputacion del dueño con ese aldeano.
     */
    private int applyOwnerPrices(Villager v) {
        clearSpecialPrices(v); // partir de precios limpios (el descuento se suma)
        int reputation = v.getGossips().getReputation(owner, g -> true);
        if (reputation != 0) {
            for (MerchantOffer o : v.getOffers()) {
                o.addToSpecialPriceDiff(-Mth.floor((float) reputation * o.getPriceMultiplier()));
            }
        }
        return reputation;
    }

    private void clearSpecialPrices(Villager v) {
        for (MerchantOffer o : v.getOffers()) o.resetSpecialPriceDiff();
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

    // ---------------------------------------------------------------- cosechar

    private enum HarvestState { SCAN, HARVEST, DELIVER }

    private static final int CARGO_MAX_STACKS = 36; // como un inventario de jugador

    private BlockPos harvestCenter;
    private int harvestRadius = 100;
    private HarvestState harvestState = HarvestState.SCAN;
    private CloneHarvester.Scan scan;
    private List<BlockPos> crops = new ArrayList<>();
    private List<CloneSorter.Group> farmChests = new ArrayList<>();
    private BlockPos harvestTarget;
    private final Map<Item, Integer> cargo = new LinkedHashMap<>();
    private Item deliverItem;
    private CloneSorter.Group deliverGroup;
    private final Set<BlockPos> deliverIgnored = new HashSet<>();
    private final Set<Item> deliverBlocked = new HashSet<>();
    private int harvestedRound;
    private boolean harvestIdleReported;
    private boolean noChestReported;

    /** Asigna la tarea de cosechar alrededor de la posicion actual del clon. */
    public void assignHarvest(int radius) {
        dropCarried(); // si venia de un trueque, suelta lo que llevaba (la carga de cosecha tambien)
        give = null;
        receive = null;
        resetPatrol();
        targetChest = null;
        targetVillager = null;
        sortCenter = null;
        sortMove = null;
        harvestCenter = clone.blockPosition();
        harvestRadius = Math.max(8, Math.min(100, radius));
        harvestState = HarvestState.SCAN;
        scan = null;
        crops = new ArrayList<>();
        farmChests = new ArrayList<>();
        harvestTarget = null;
        deliverItem = null;
        deliverGroup = null;
        deliverIgnored.clear();
        deliverBlocked.clear();
        harvestedRound = 0;
        harvestIdleReported = false;
        noChestReported = false;
        cooldown = 0;
        clone.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        saveState();
        CloneChunkLoader.refresh(this);
    }

    private void harvestTick() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        switch (harvestState) {
            case SCAN -> harvestScan();
            case HARVEST -> harvestStep();
            case DELIVER -> harvestDeliver();
        }
    }

    /** Escanea cultivos listos y cofres por tandas (no bloquea el servidor). */
    private void harvestScan() {
        if (scan == null) scan = new CloneHarvester.Scan(level(), harvestCenter, harvestRadius);
        if (!scan.step(25000)) return;

        crops = scan.crops;
        farmChests = CloneSorter.groupChests(level(), scan.chests);
        scan = null;

        // Orden de barrido en serpentina por franjas de 8 bloques, empezando por lo mas cercano al clon
        crops.sort(Comparator.comparingInt((BlockPos p) -> Math.floorDiv(p.getZ(), 8))
                .thenComparingInt(p -> (Math.floorDiv(p.getZ(), 8) & 1) == 0 ? p.getX() : -p.getX())
                .thenComparingInt(BlockPos::getZ)
                .thenComparingInt(BlockPos::getY));
        if (!crops.isEmpty()) {
            Vec3 me = clone.position();
            int nearest = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < crops.size(); i++) {
                BlockPos p = crops.get(i);
                double dx = p.getX() + .5 - me.x;
                double dz = p.getZ() + .5 - me.z;
                double d = dx * dx + dz * dz;
                if (d < best) {
                    best = d;
                    nearest = i;
                }
            }
            Collections.rotate(crops, -nearest);
        }

        deliverIgnored.clear();
        deliverBlocked.clear();
        harvestedRound = 0;
        harvestTarget = null;
        harvestState = HarvestState.HARVEST;
    }

    private void harvestStep() {
        if (cargoStacks() >= CARGO_MAX_STACKS) {
            startDelivery();
            return;
        }
        if (harvestTarget == null) {
            harvestTarget = pickNextCrop();
            if (harvestTarget == null) { // no quedan cultivos: entregar y terminar la ronda
                if (!cargo.isEmpty()) {
                    startDelivery();
                } else {
                    finishRound();
                }
                return;
            }
            resetNavigation();
        }

        ServerLevel lvl = level();
        if (!lvl.hasChunkAt(harvestTarget)
                || !CloneHarvester.isMature(lvl, harvestTarget, lvl.getBlockState(harvestTarget))) {
            harvestTarget = null; // ya lo cosecho alguien o el chunk se descargo
            return;
        }
        switch (walkToNavigated(Vec3.atBottomCenterOf(harvestTarget))) {
            case ARRIVED -> {
                Map<Item, Integer> got = CloneHarvester.harvest(lvl, harvestTarget, clone);
                clone.swing(InteractionHand.MAIN_HAND);
                if (got != null) {
                    for (Map.Entry<Item, Integer> e : got.entrySet()) cargo.merge(e.getKey(), e.getValue(), Integer::sum);
                    harvestedRound++;
                    saveCargo();
                }
                harvestTarget = null;
                resetNavigation();
                cooldown = 3;
            }
            case STUCK -> { // inalcanzable: se salta
                harvestTarget = null;
                resetNavigation();
            }
            default -> { }
        }
    }

    /** Siguiente cultivo: el mas cercano entre los primeros de la lista (mantiene el barrido ordenado). */
    private BlockPos pickNextCrop() {
        if (crops.isEmpty()) return null;
        Vec3 me = clone.position();
        int limit = Math.min(60, crops.size());
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < limit; i++) {
            BlockPos p = crops.get(i);
            double dx = p.getX() + .5 - me.x;
            double dy = p.getY() - me.y;
            double dz = p.getZ() + .5 - me.z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return crops.remove(best);
    }

    private void finishRound() {
        if (harvestedRound > 0) {
            tell("Ronda terminada: " + harvestedRound + " cultivos cosechados y replantados.");
            harvestIdleReported = false;
        } else if (!harvestIdleReported) {
            tell("No hay cultivos listos en " + harvestRadius + " bloques (solo veo chunks cargados). Vuelvo a mirar en 30 s.");
            harvestIdleReported = true;
        }
        harvestedRound = 0;
        harvestState = HarvestState.SCAN;
        scan = null;
        cooldown = 600; // esperar 30 s a que crezcan mas cultivos
    }

    private void startDelivery() {
        harvestState = HarvestState.DELIVER;
        deliverItem = null;
        deliverGroup = null;
        resetNavigation();
    }

    /** Lleva lo cosechado a un cofre: preferentemente uno que ya tenga ese item. */
    private void harvestDeliver() {
        if (cargo.isEmpty()) {
            endDelivery();
            return;
        }
        if (deliverItem == null || !cargo.containsKey(deliverItem)) {
            deliverItem = null;
            for (Item it : cargo.keySet()) {
                if (!deliverBlocked.contains(it)) {
                    deliverItem = it;
                    break;
                }
            }
            if (deliverItem == null) { // todo lo que lleva esta sin cofre disponible
                if (!noChestReported) {
                    tell("No encuentro un cofre con espacio para lo que llevo (radio " + harvestRadius
                            + "). Pon un cofre o vacia uno.");
                    noChestReported = true;
                }
                deliverBlocked.clear();
                deliverIgnored.clear();
                if (cargoStacks() >= CARGO_MAX_STACKS) {
                    cooldown = 200; // carga llena y sin donde dejarla: esperar
                } else {
                    endDelivery();  // seguir cosechando y reintentar mas tarde
                }
                return;
            }
        }
        if (deliverGroup == null) {
            deliverGroup = CloneSorter.pickDestination(level(), farmChests, deliverItem,
                    clone.position(), deliverIgnored);
            if (deliverGroup == null) {
                deliverBlocked.add(deliverItem);
                deliverItem = null;
                return;
            }
            resetNavigation();
        }
        BlockPos target = deliverGroup.nearestPart(clone.position());
        switch (walkToNavigated(Vec3.atBottomCenterOf(target))) {
            case ARRIVED -> {
                int amount = cargo.get(deliverItem);
                int left = CloneSorter.insertInto(level(), deliverGroup, deliverItem, amount);
                int stored = amount - left;
                if (left <= 0) cargo.remove(deliverItem); else cargo.put(deliverItem, left);
                saveCargo();
                clone.swing(InteractionHand.MAIN_HAND);
                if (stored > 0) {
                    tell("Guarde " + stored + "x " + ItemLookup.shortName(deliverItem) + " en un cofre.");
                    noChestReported = false;
                } else {
                    deliverIgnored.add(deliverGroup.anchor); // estaba lleno: probar con otro
                }
                deliverGroup = null;
                resetNavigation();
                cooldown = 10;
            }
            case STUCK -> {
                deliverIgnored.add(deliverGroup.anchor);
                deliverGroup = null;
                resetNavigation();
            }
            default -> { }
        }
    }

    private void endDelivery() {
        deliverItem = null;
        deliverGroup = null;
        resetNavigation();
        if (crops.isEmpty() && harvestTarget == null) finishRound(); else harvestState = HarvestState.HARVEST;
    }

    private int cargoStacks() {
        int stacks = 0;
        for (Map.Entry<Item, Integer> e : cargo.entrySet()) {
            int max = Math.max(1, new ItemStack(e.getKey()).getMaxStackSize());
            stacks += (e.getValue() + max - 1) / max;
        }
        return stacks;
    }

    // La carga se guarda como "item*cantidad;item*cantidad" en un tag: sobrevive a reinicios sin perder nada.
    private void saveCargo() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, Integer> e : cargo.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (!sb.isEmpty()) sb.append(';');
            sb.append(ItemLookup.fullName(e.getKey())).append('*').append(e.getValue());
        }
        CloneManager.setTag(clone, "playerclones:cargo=", sb.isEmpty() ? null : sb.toString());
    }

    private void loadCargo() {
        cargo.clear();
        String tag = CloneManager.getTag(clone, "playerclones:cargo=");
        if (tag == null || tag.isBlank()) return;
        for (String part : tag.split(";")) {
            int star = part.lastIndexOf('*');
            if (star < 0) continue;
            Item item = ItemLookup.byName(part.substring(0, star));
            try {
                int n = Integer.parseInt(part.substring(star + 1));
                if (item != null && n > 0) cargo.merge(item, n, Integer::sum);
            } catch (NumberFormatException ignored) { }
        }
    }

    private void dropCargo() {
        for (Map.Entry<Item, Integer> e : cargo.entrySet()) dropItems(e.getKey(), e.getValue());
        cargo.clear();
        CloneManager.setTag(clone, "playerclones:cargo=", null);
    }

    // ----------------------------------------------------------- ordenar cofres

    private enum SortState { PLAN, TO_SOURCE, TO_DEST }

    private BlockPos sortCenter;
    private int sortRadius = 16;
    private SortState sortState = SortState.PLAN;
    private CloneSorter.Move sortMove;
    private final Set<String> sortBlocked = new HashSet<>();
    private int sortMovedTotal;
    private int sortIdleCycles;
    private boolean sortWorking;
    private boolean sortIdleReported;

    /** Asigna la tarea de ordenar los cofres alrededor de la posicion actual del clon. */
    public void assignSort(int radius) {
        dropCarried(); // si venia de un trueque, suelta lo que llevaba
        give = null;
        receive = null;
        resetPatrol();
        targetChest = null;
        targetVillager = null;
        harvestCenter = null;
        sortCenter = clone.blockPosition();
        sortRadius = Math.max(4, Math.min(24, radius));
        sortState = SortState.PLAN;
        sortMove = null;
        sortBlocked.clear();
        sortMovedTotal = 0;
        sortIdleCycles = 0;
        sortWorking = false;
        sortIdleReported = false;
        cooldown = 0;
        clone.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        saveState();
        CloneChunkLoader.refresh(this);
    }

    private void sortTick() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        switch (sortState) {
            case PLAN -> sortPlan();
            case TO_SOURCE -> sortToSource();
            case TO_DEST -> sortToDest();
        }
    }

    private void sortPlan() {
        List<CloneSorter.Group> groups = CloneSorter.scan(level(), sortCenter, sortRadius);
        CloneSorter.Move move = CloneSorter.plan(level(), groups, clone.position(), sortBlocked);
        if (move == null) {
            if (sortWorking) {
                sortWorking = false;
                tell("Termine de ordenar los cofres (" + sortMovedTotal + " items movidos).");
            } else if (!sortIdleReported) {
                tell("No hay nada que ordenar (hacen falta 2 o mas cofres, items repartidos y espacio).");
            }
            sortIdleReported = true;
            if (++sortIdleCycles % 12 == 0) sortBlocked.clear(); // reintentar bloqueados cada ~1 min
            cooldown = 100;
            return;
        }
        sortIdleReported = false;
        sortWorking = true;
        sortMove = move;
        sortState = SortState.TO_SOURCE;
        resetNavigation();
    }

    private void sortToSource() {
        CloneSorter.Move m = sortMove;
        if (m == null || !CloneSorter.hasAny(level(), m.source(), m.items())) {
            sortMove = null;
            sortState = SortState.PLAN; // alguien vacio el cofre mientras tanto
            return;
        }
        BlockPos target = m.source().nearestPart(clone.position());
        switch (walkToNavigated(Vec3.atBottomCenterOf(target))) {
            case ARRIVED -> {
                clone.swing(InteractionHand.MAIN_HAND);
                clone.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(m.items().get(0)));
                sortState = SortState.TO_DEST;
                resetNavigation();
                cooldown = 10;
            }
            case STUCK -> {
                sortBlocked.add(m.key());
                sortMove = null;
                sortState = SortState.PLAN;
                resetNavigation();
            }
            default -> { }
        }
    }

    private void sortToDest() {
        CloneSorter.Move m = sortMove;
        if (m == null) {
            sortState = SortState.PLAN;
            return;
        }
        BlockPos target = m.dest().nearestPart(clone.position());
        switch (walkToNavigated(Vec3.atBottomCenterOf(target))) {
            case ARRIVED -> {
                // El traspaso se hace de golpe al llegar: no hay items "en transito" que se puedan perder.
                Map<Item, Integer> moved = CloneSorter.transfer(level(), m.source(), m.dest(), m.items());
                clone.swing(InteractionHand.MAIN_HAND);
                clone.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                int total = 0;
                StringBuilder text = new StringBuilder();
                int shown = 0;
                for (Map.Entry<Item, Integer> e : moved.entrySet()) {
                    total += e.getValue();
                    if (shown++ < 3) {
                        if (!text.isEmpty()) text.append(", ");
                        text.append(e.getValue()).append("x ").append(ItemLookup.shortName(e.getKey()));
                    }
                }
                if (moved.size() > 3) text.append(", ...");
                if (total == 0) {
                    sortBlocked.add(m.key());
                } else {
                    sortMovedTotal += total;
                    tell("Movi " + text + ".");
                }
                sortMove = null;
                sortState = SortState.PLAN;
                resetNavigation();
                cooldown = 10;
            }
            case STUCK -> {
                sortBlocked.add(m.key());
                sortMove = null;
                sortState = SortState.PLAN;
                clone.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                resetNavigation();
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------- movimiento

    private void resetNavigation() {
        navigationTarget = null;
        navigationPath = List.of();
        navigationStep = 0;
        navigationBlockedTicks = 0;
        navigationRetryDelay = 0;
        navigationPlanned = false;
        navigationFailures = 0;
    }

    /**
     * Camina hasta el objetivo planificando el camino con A* desde el principio (rodea paredes,
     * sube/baja desniveles y abre puertas). Si no hay camino, se acerca en linea recta y, si sigue
     * sin avanzar, devuelve STUCK para que el objetivo se descarte.
     */
    private Walk walkToNavigated(Vec3 target) {
        Vec3 current = clone.position();
        if (current.distanceToSqr(target) <= REACH * REACH && Math.abs(current.y - target.y) < 3) {
            resetNavigation();
            return Walk.ARRIVED;
        }

        // Objetivo nuevo (otro cofre/aldeano/punto) o que se movio mucho: planificar de nuevo.
        if (navigationTarget == null || navigationTarget.distanceToSqr(target) > 2.25) {
            if (navigationTarget == null || navigationTarget.distanceToSqr(target) > 16.0) resetNavigation();
            navigationTarget = target;
            navigationPlanned = false;
        }
        if (navigationRetryDelay > 0) navigationRetryDelay--;

        if (!navigationPlanned && navigationRetryDelay == 0) {
            navigationPath = ClonePathfinder.find(level(), current, target);
            navigationStep = 0;
            navigationPlanned = true;
            navigationBlockedTicks = 0;
            navigationRetryDelay = 10; // como mucho 2 calculos por segundo
        }

        // Seguir el camino calculado
        while (navigationStep < navigationPath.size()) {
            Vec3 step = navigationPath.get(navigationStep);
            Vec3 now = clone.position();
            double hx = now.x - step.x;
            double hz = now.z - step.z;
            if (now.distanceToSqr(step) < .65 * .65 || (hx * hx + hz * hz < .3 * .3 && now.y > step.y)) {
                navigationStep++; // llego a este punto (o lo esta sobrevolando en una caida)
                continue;
            }
            openDoorsAround(step);
            Walk result = walkToRaw(step, .55);
            if (result == Walk.ARRIVED) {
                navigationStep++;
                continue;
            }
            if (result == Walk.STUCK) {
                // Algo bloquea este tramo: recalcular desde donde esta.
                navigationPath = List.of();
                navigationStep = 0;
                navigationPlanned = false;
                navigationRetryDelay = 0;
                if (++navigationFailures >= 4) {
                    resetNavigation();
                    return Walk.STUCK;
                }
            }
            return Walk.MOVING;
        }
        navigationPath = List.of();
        navigationStep = 0;

        // Sin camino (o ya recorrido): acercarse en linea recta.
        Vec3 before = clone.position();
        Walk direct = walkToRaw(target, REACH);
        if (direct == Walk.ARRIVED) {
            resetNavigation();
            return direct;
        }
        boolean noProgress = direct == Walk.STUCK
                || Math.pow(clone.getX() - before.x, 2) + Math.pow(clone.getZ() - before.z, 2) < .0004;
        navigationBlockedTicks = noProgress ? navigationBlockedTicks + 1 : 0;
        if (navigationBlockedTicks >= 12 && navigationRetryDelay == 0) {
            navigationPlanned = false; // replanificar desde la posicion actual
            navigationBlockedTicks = 0;
        }
        if (direct == Walk.STUCK) {
            resetNavigation();
            return Walk.STUCK;
        }
        return Walk.MOVING;
    }

    /** Abre las puertas/portones en el camino inmediato del clon. */
    private void openDoorsAround(Vec3 step) {
        ServerLevel w = level();
        BlockPos s = BlockPos.containing(step);
        BlockPos me = clone.blockPosition();
        List<BlockPos> cells = new ArrayList<>(List.of(s, s.above(), me, me.above()));
        if (navigationStep + 1 < navigationPath.size()) {
            BlockPos n = BlockPos.containing(navigationPath.get(navigationStep + 1));
            cells.add(n);
            cells.add(n.above());
        }
        for (BlockPos c : cells) {
            BlockPos opened = ClonePathfinder.openDoor(w, c);
            if (opened != null) openedDoors.add(opened);
        }
    }

    /** Cierra las puertas que el clon abrio cuando ya se alejo. */
    private void closeDoorsBehind() {
        if (openedDoors.isEmpty() || clone.tickCount % 10 != 0) return;
        Vec3 p = clone.position();
        var it = openedDoors.iterator();
        while (it.hasNext()) {
            BlockPos d = it.next();
            double dx = p.x - (d.getX() + .5);
            double dz = p.z - (d.getZ() + .5);
            if (dx * dx + dz * dz > 9.0 || Math.abs(p.y - d.getY()) > 4) {
                ClonePathfinder.closeDoor(level(), d);
                it.remove();
            }
        }
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
