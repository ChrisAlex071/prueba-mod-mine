package dev.playerclones;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Navegador A* sencillo para el mannequin, independiente del comercio y la patrulla.
 * Solo examina chunks cargados; no fuerza chunks adicionales ni modifica el terreno.
 * Devuelve posiciones de los pies, con escalones de hasta un bloque.
 */
final class ClonePathfinder {
    private static final int MAX_VISITED = 1800;
    private static final int MAX_DISTANCE = 40;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private record Node(BlockPos pos, double score) { }

    static List<Vec3> find(ServerLevel world, Vec3 from, Vec3 destination) {
        BlockPos start = BlockPos.containing(from);
        BlockPos goal = BlockPos.containing(destination);
        if (Math.abs(start.getX() - goal.getX()) > MAX_DISTANCE
                || Math.abs(start.getZ() - goal.getZ()) > MAX_DISTANCE) return List.of();

        // El clon puede estar en el aire por un salto; empieza por el suelo cercano.
        if (!walkable(world, start)) {
            BlockPos below = start.below();
            if (walkable(world, below)) start = below;
            else if (walkable(world, start.above())) start = start.above();
            else return List.of();
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<BlockPos, Double> distance = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        distance.put(start, 0.0);
        open.add(new Node(start, heuristic(start, goal)));

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_VISITED) {
            BlockPos current = open.poll().pos();
            if (!closed.add(current)) continue;
            if (Math.abs(current.getX() - goal.getX()) <= 1
                    && Math.abs(current.getZ() - goal.getZ()) <= 1
                    && Math.abs(current.getY() - goal.getY()) <= 2) {
                ArrayList<Vec3> path = new ArrayList<>();
                for (BlockPos p = current; p != null && !p.equals(start); p = parent.get(p)) {
                    path.add(new Vec3(p.getX() + .5, p.getY(), p.getZ() + .5));
                }
                java.util.Collections.reverse(path);
                return path;
            }
            for (int[] d : DIRECTIONS) {
                int x = current.getX() + d[0];
                int z = current.getZ() + d[1];
                if (Math.abs(x - start.getX()) > MAX_DISTANCE
                        || Math.abs(z - start.getZ()) > MAX_DISTANCE) continue;
                // Preferir piso a nivel, después subir o bajar un bloque.
                for (int dy : new int[]{0, 1, -1}) {
                    BlockPos next = new BlockPos(x, current.getY() + dy, z);
                    if (!walkable(world, next) || closed.contains(next)) continue;
                    // Para subir se necesita espacio también sobre la cabeza actual.
                    if (dy > 0 && !free(world, current.above(2))) continue;
                    double tentative = distance.get(current) + 1 + (dy == 0 ? 0 : .35);
                    if (tentative >= distance.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    parent.put(next, current);
                    distance.put(next, tentative);
                    open.add(new Node(next, tentative + heuristic(next, goal)));
                }
            }
        }
        return List.of();
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ())
                + .6 * Math.abs(a.getY() - b.getY());
    }

    private static boolean free(ServerLevel world, BlockPos p) {
        return world.hasChunkAt(p)
                && world.getBlockState(p).getCollisionShape(world, p).isEmpty()
                && world.getFluidState(p).isEmpty();
    }

    private static boolean walkable(ServerLevel world, BlockPos feet) {
        if (!free(world, feet) || !free(world, feet.above())) return false;
        BlockPos floor = feet.below();
        return world.hasChunkAt(floor)
                && !world.getBlockState(floor).getCollisionShape(world, floor).isEmpty()
                && world.getFluidState(floor).isEmpty();
    }

    private ClonePathfinder() { }
}
