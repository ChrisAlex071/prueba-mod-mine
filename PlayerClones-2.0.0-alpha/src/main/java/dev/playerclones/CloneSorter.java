package dev.playerclones;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

/**
 * Logica para ordenar cofres: un cofre "casa" por tipo de item.
 *
 * Reglas:
 *  - Un cofre doble cuenta como UN solo cofre.
 *  - La casa de cada item es el cofre que ya tiene mas cantidad de ese item. Los items se reparten
 *    de mayor a menor cantidad total y, si se puede, cada cofre es casa de un solo item; si todos los
 *    cofres que tienen un item ya son casa de otro, se usa un cofre vacio, y si no hay ninguno, el item
 *    se queda donde esta.
 *  - Los traspasos conservan los datos de cada pila (encantamientos, nombre, durabilidad, etc.).
 */
final class CloneSorter {
    private static final int HEIGHT = 6;

    static final class Group {
        final BlockPos anchor;
        final List<BlockPos> parts;

        Group(BlockPos anchor, List<BlockPos> parts) {
            this.anchor = anchor;
            this.parts = parts;
        }

        BlockPos nearestPart(Vec3 from) {
            BlockPos best = parts.get(0);
            double bestD = Double.MAX_VALUE;
            for (BlockPos p : parts) {
                double dx = p.getX() + .5 - from.x;
                double dy = p.getY() + .5 - from.y;
                double dz = p.getZ() + .5 - from.z;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            return best;
        }

        @Override public boolean equals(Object o) { return o instanceof Group g && g.anchor.equals(anchor); }
        @Override public int hashCode() { return anchor.hashCode(); }
    }

    record Move(Group source, Group dest, List<Item> items) {
        String key() { return source.anchor.asLong() + ">" + dest.anchor.asLong(); }
    }

    private CloneSorter() { }

    // ------------------------------------------------------------- escaneo

    static List<Group> scan(ServerLevel level, BlockPos center, int radius) {
        List<Group> groups = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(
                center.offset(-radius, -HEIGHT, -radius), center.offset(radius, HEIGHT, radius))) {
            if (!level.hasChunkAt(p) || !(level.getBlockEntity(p) instanceof ChestBlockEntity)) continue;
            BlockPos pos = p.immutable();
            if (seen.contains(pos)) continue;

            List<BlockPos> parts = new ArrayList<>();
            parts.add(pos);
            BlockState state = level.getBlockState(pos);
            if (state.hasProperty(BlockStateProperties.CHEST_TYPE)
                    && state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
                BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
                if (level.hasChunkAt(other) && level.getBlockEntity(other) instanceof ChestBlockEntity) {
                    parts.add(other);
                }
            }
            seen.addAll(parts);
            BlockPos anchor = parts.get(0);
            for (BlockPos q : parts) {
                if (q.asLong() < anchor.asLong()) anchor = q;
            }
            groups.add(new Group(anchor, parts));
        }
        return groups;
    }

    private static List<Container> containers(ServerLevel level, Group g) {
        List<Container> out = new ArrayList<>();
        for (BlockPos p : g.parts) {
            if (level.hasChunkAt(p) && level.getBlockEntity(p) instanceof ChestBlockEntity chest) out.add(chest);
        }
        return out;
    }

    private static Map<Item, Integer> counts(ServerLevel level, Group g) {
        Map<Item, Integer> map = new HashMap<>();
        for (Container c : containers(level, g)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) map.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return map;
    }

    static boolean hasAny(ServerLevel level, Group g, List<Item> items) {
        Map<Item, Integer> c = counts(level, g);
        for (Item item : items) {
            if (c.getOrDefault(item, 0) > 0) return true;
        }
        return false;
    }

    private static boolean hasRoomFor(ServerLevel level, Group g, Item item) {
        for (Container c : containers(level, g)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || (s.is(item) && s.getCount() < s.getMaxStackSize())) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ planificacion

    /** Devuelve el siguiente traspaso a hacer (el mas cercano al clon) o null si no hay nada que mover. */
    static Move plan(ServerLevel level, List<Group> groups, Vec3 clonePos, Set<String> blocked) {
        if (groups.size() < 2) return null;

        Map<Group, Map<Item, Integer>> inv = new LinkedHashMap<>();
        Map<Item, Integer> totals = new HashMap<>();
        for (Group g : groups) {
            Map<Item, Integer> c = counts(level, g);
            inv.put(g, c);
            c.forEach((item, n) -> totals.merge(item, n, Integer::sum));
        }

        // 1) Elegir la "casa" de cada item (de mayor a menor cantidad total)
        List<Item> order = new ArrayList<>(totals.keySet());
        order.sort(Comparator.comparingInt((Item i) -> totals.get(i)).reversed()
                .thenComparing(i -> ItemLookup.fullName(i)));

        Map<Item, Group> home = new HashMap<>();
        Set<Group> taken = new HashSet<>();
        for (Item item : order) {
            List<Group> holders = new ArrayList<>();
            for (Group g : groups) {
                if (inv.get(g).getOrDefault(item, 0) > 0) holders.add(g);
            }
            holders.sort(Comparator.comparingInt((Group g) -> inv.get(g).getOrDefault(item, 0)).reversed()
                    .thenComparingLong(g -> g.anchor.asLong()));

            Group pick = null;
            for (Group g : holders) {
                if (!taken.contains(g)) { pick = g; break; }
            }
            if (pick == null) { // todos los que lo tienen ya son casa de otro item: usar un cofre vacio
                for (Group g : groups) {
                    if (!taken.contains(g) && inv.get(g).isEmpty()) { pick = g; break; }
                }
            }
            if (pick == null) pick = holders.get(0); // sin sitio: comparte cofre con quien ya lo tiene
            home.put(item, pick);
            taken.add(pick);
        }

        // 2) Buscar el traspaso mas cercano: items fuera de su casa y con espacio en ella
        List<Group> bySource = new ArrayList<>(groups);
        bySource.sort(Comparator.comparingDouble((Group g) -> {
            BlockPos p = g.nearestPart(clonePos);
            double dx = p.getX() + .5 - clonePos.x;
            double dz = p.getZ() + .5 - clonePos.z;
            return dx * dx + dz * dz;
        }));

        for (Group src : bySource) {
            Map<Group, List<Item>> byDest = new LinkedHashMap<>();
            for (Item item : inv.get(src).keySet()) {
                Group dest = home.get(item);
                if (dest == null || dest.equals(src)) continue;
                if (blocked.contains(new Move(src, dest, List.of()).key())) continue;
                if (!hasRoomFor(level, dest, item)) continue;
                byDest.computeIfAbsent(dest, k -> new ArrayList<>()).add(item);
            }
            for (Map.Entry<Group, List<Item>> e : byDest.entrySet()) {
                return new Move(src, e.getKey(), e.getValue());
            }
        }
        return null;
    }

    // ------------------------------------------------------------ traspaso

    /**
     * Mueve de source a dest todas las pilas de los items indicados (las que quepan).
     * Devuelve cuanto se movio de cada item. Conserva los componentes de cada pila.
     */
    static Map<Item, Integer> transfer(ServerLevel level, Group source, Group dest, List<Item> items) {
        Map<Item, Integer> moved = new LinkedHashMap<>();
        List<Container> from = containers(level, source);
        List<Container> to = containers(level, dest);
        if (from.isEmpty() || to.isEmpty()) return moved;

        for (Container c : from) {
            boolean changed = false;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !items.contains(s.getItem())) continue;
                int before = s.getCount();
                int left = insert(to, s.copy());
                int done = before - left;
                if (done <= 0) continue;
                if (left <= 0) c.setItem(i, ItemStack.EMPTY); else s.shrink(done);
                moved.merge(s.getItem(), done, Integer::sum);
                changed = true;
            }
            if (changed) c.setChanged();
        }
        for (Container d : to) d.setChanged();
        return moved;
    }

    /** Inserta la pila en los contenedores y devuelve cuantos items NO cupieron. */
    private static int insert(List<Container> to, ItemStack stack) {
        for (Container d : to) { // 1) completar pilas existentes
            for (int j = 0; j < d.getContainerSize() && !stack.isEmpty(); j++) {
                ItemStack t = d.getItem(j);
                if (!t.isEmpty() && ItemStack.isSameItemSameComponents(t, stack)
                        && t.getCount() < t.getMaxStackSize()) {
                    int add = Math.min(stack.getCount(), t.getMaxStackSize() - t.getCount());
                    t.grow(add);
                    stack.shrink(add);
                }
            }
        }
        for (Container d : to) { // 2) usar espacios vacios
            for (int j = 0; j < d.getContainerSize() && !stack.isEmpty(); j++) {
                if (d.getItem(j).isEmpty()) {
                    int add = Math.min(stack.getCount(), stack.getMaxStackSize());
                    ItemStack put = stack.copy();
                    put.setCount(add);
                    d.setItem(j, put);
                    stack.shrink(add);
                }
            }
        }
        return stack.isEmpty() ? 0 : stack.getCount();
    }
}
