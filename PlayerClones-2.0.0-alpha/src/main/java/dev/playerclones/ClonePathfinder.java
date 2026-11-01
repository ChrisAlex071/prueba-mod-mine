package dev.playerclones;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * Navegador A* para el clon (version 2).
 *  - 8 direcciones (diagonales sin cortar esquinas), subir 1 bloque y bajar hasta 3.
 *  - Atraviesa puertas de madera y portones (el clon las abre al pasar, ver CloneWorker).
 *  - Evita agua, lava, fuego, cactus, telaranas, magma, bayas y nieve en polvo.
 *  - Si no hay camino completo, devuelve el tramo que mas acerca al destino.
 * Solo examina chunks cargados y no modifica el terreno (salvo openDoor/closeDoor).
 */
final class ClonePathfinder {
    private static final int MAX_VISITED = 6000;
    private static final int MAX_DISTANCE = 48;
    private static final int MAX_DROP = 3;
    private static final double GOAL_RADIUS = 1.6; // casilla vecina (incluye diagonal) al objetivo

    private record Node(BlockPos pos, double score) { }

    static List<Vec3> find(ServerLevel world, Vec3 from, Vec3 destination) {
        BlockPos start = BlockPos.containing(from);
        BlockPos goal = BlockPos.containing(destination);
        if (Math.abs(start.getX() - goal.getX()) > MAX_DISTANCE
                || Math.abs(start.getZ() - goal.getZ()) > MAX_DISTANCE) return List.of();

        // El clon puede estar en el aire (salto) o sobre un bloque raro: busca el suelo cercano.
        if (!walkable(world, start)) {
            BlockPos fixed = null;
            for (BlockPos c : new BlockPos[] {start.below(), start.above(), start.below(2)}) {
                if (walkable(world, c)) { fixed = c; break; }
            }
            if (fixed == null) return List.of();
            start = fixed;
        }
        final BlockPos origin = start;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<BlockPos, Double> cost = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        cost.put(origin, 0.0);
        open.add(new Node(origin, heuristic(origin, destination)));

        BlockPos best = origin;
        double bestH = heuristic(origin, destination);

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_VISITED) {
            BlockPos cur = open.poll().pos();
            if (!closed.add(cur)) continue;
            if (isGoal(cur, destination)) return build(cur, origin, parent);

            double h = heuristic(cur, destination);
            if (h < bestH) {
                bestH = h;
                best = cur;
            }

            double base = cost.get(cur);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int x = cur.getX() + dx;
                    int z = cur.getZ() + dz;
                    if (Math.abs(x - origin.getX()) > MAX_DISTANCE
                            || Math.abs(z - origin.getZ()) > MAX_DISTANCE) continue;

                    if (dx != 0 && dz != 0) {
                        // Diagonal: solo en el mismo nivel y sin rozar esquinas ni puertas.
                        BlockPos a = cur.offset(dx, 0, 0);
                        BlockPos b = cur.offset(0, 0, dz);
                        BlockPos n = new BlockPos(x, cur.getY(), z);
                        if (!clear(world, a) || !clear(world, b) || !walkable(world, n)) continue;
                        if (isDoor(world.getBlockState(a)) || isDoor(world.getBlockState(b))
                                || isDoor(world.getBlockState(n))) continue;
                        relax(cur, n, base + 1.41, destination, cost, parent, closed, open);
                        continue;
                    }

                    BlockPos level = new BlockPos(x, cur.getY(), z);
                    if (walkable(world, level)) {
                        relax(cur, level, base + 1, destination, cost, parent, closed, open);
                        continue;
                    }
                    // Subir un bloque (necesita espacio sobre la cabeza actual para saltar).
                    BlockPos up = level.above();
                    if (walkable(world, up) && passable(world, cur.above(2))) {
                        relax(cur, up, base + 1.5, destination, cost, parent, closed, open);
                        continue;
                    }
                    // Bajar hasta MAX_DROP bloques (sin dano de caida).
                    if (passable(world, level) && passable(world, level.above())) {
                        for (int k = 1; k <= MAX_DROP; k++) {
                            BlockPos down = new BlockPos(x, cur.getY() - k, z);
                            if (walkable(world, down)) {
                                relax(cur, down, base + 1 + .4 * k, destination, cost, parent, closed, open);
                                break;
                            }
                            if (!passable(world, down)) break;
                        }
                    }
                }
            }
        }

        // Sin camino completo: acercarse todo lo posible (si de verdad se gana terreno).
        if (!best.equals(origin) && bestH < heuristic(origin, destination) - 2.0) {
            return build(best, origin, parent);
        }
        return List.of();
    }

    // ------------------------------------------------------------ puertas

    /** Puerta de madera o porton (las de hierro no cuentan: necesitan redstone). */
    static boolean isDoor(BlockState s) {
        return (s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.FENCE_GATES))
                && s.hasProperty(BlockStateProperties.OPEN);
    }

    /** Abre la puerta/porton en p (ambas mitades). Devuelve su posicion base, o null si no hizo nada. */
    static BlockPos openDoor(ServerLevel world, BlockPos p) {
        return setDoor(world, p, true);
    }

    static void closeDoor(ServerLevel world, BlockPos p) {
        setDoor(world, p, false);
    }

    private static BlockPos setDoor(ServerLevel world, BlockPos p, boolean open) {
        if (!world.hasChunkAt(p)) return null;
        BlockState s = world.getBlockState(p);
        if (!isDoor(s) || s.getValue(BlockStateProperties.OPEN) == open) return null;
        boolean tall = s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
        BlockPos base = tall && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                ? p.below() : p;
        setHalf(world, base, open);
        if (tall) setHalf(world, base.above(), open);
        return base;
    }

    private static void setHalf(ServerLevel world, BlockPos p, boolean open) {
        BlockState s = world.getBlockState(p);
        if (isDoor(s)) world.setBlock(p, s.setValue(BlockStateProperties.OPEN, open), 10);
    }

    // ------------------------------------------------------------ utilidades

    private static void relax(BlockPos from, BlockPos to, double newCost, Vec3 destination,
                              Map<BlockPos, Double> cost, Map<BlockPos, BlockPos> parent,
                              Set<BlockPos> closed, PriorityQueue<Node> open) {
        if (closed.contains(to) || newCost >= cost.getOrDefault(to, Double.POSITIVE_INFINITY)) return;
        cost.put(to, newCost);
        parent.put(to, from);
        open.add(new Node(to, newCost + heuristic(to, destination)));
    }

    private static List<Vec3> build(BlockPos end, BlockPos origin, Map<BlockPos, BlockPos> parent) {
        ArrayList<Vec3> path = new ArrayList<>();
        for (BlockPos p = end; p != null && !p.equals(origin); p = parent.get(p)) {
            path.add(new Vec3(p.getX() + .5, p.getY(), p.getZ() + .5));
        }
        Collections.reverse(path);
        return path;
    }

    private static boolean isGoal(BlockPos p, Vec3 destination) {
        double dx = p.getX() + .5 - destination.x;
        double dz = p.getZ() + .5 - destination.z;
        return dx * dx + dz * dz <= GOAL_RADIUS * GOAL_RADIUS
                && Math.abs(p.getY() - destination.y) <= 2;
    }

    private static double heuristic(BlockPos p, Vec3 destination) {
        double dx = p.getX() + .5 - destination.x;
        double dz = p.getZ() + .5 - destination.z;
        return Math.max(0, Math.sqrt(dx * dx + dz * dz) - GOAL_RADIUS)
                + .3 * Math.abs(p.getY() - destination.y);
    }

    private static boolean hazard(BlockState s) {
        return s.is(BlockTags.FIRE) || s.is(BlockTags.CAMPFIRES)
                || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.COBWEB)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.MAGMA_BLOCK);
    }

    /** Casilla donde cabe el cuerpo del clon: sin colision (o puerta), sin liquido ni peligro. */
    private static boolean passable(ServerLevel world, BlockPos p) {
        if (!world.hasChunkAt(p)) return false;
        BlockState s = world.getBlockState(p);
        if (!world.getFluidState(p).isEmpty() || hazard(s)) return false;
        return s.getCollisionShape(world, p).isEmpty() || isDoor(s);
    }

    private static boolean clear(ServerLevel world, BlockPos feet) {
        return passable(world, feet) && passable(world, feet.above());
    }

    private static boolean walkable(ServerLevel world, BlockPos feet) {
        if (!clear(world, feet)) return false;
        BlockPos floor = feet.below();
        if (!world.hasChunkAt(floor)) return false;
        BlockState f = world.getBlockState(floor);
        return !f.getCollisionShape(world, floor).isEmpty()
                && world.getFluidState(floor).isEmpty()
                && !hazard(f);
    }

    private ClonePathfinder() { }
}
