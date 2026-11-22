package dev.playerclones;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Cosecha para los clones: reconoce cultivos listos, los cosecha, los replanta y escanea la zona
 * de forma incremental (unos miles de bloques por tick) para no congelar el servidor.
 *
 * Cultivos que reconoce: trigo, zanahorias, patatas, remolachas, verruga del Nether, cacao,
 * bayas dulces y melones/calabazas unidos a un tallo.
 */
final class CloneHarvester {
    /** Altura a la que se buscan cultivos, por encima y por debajo del centro. */
    static final int VERTICAL = 8;
    /** Altura a la que se buscan cofres, por encima y por debajo del centro. */
    static final int CHEST_VERTICAL = 15;

    private CloneHarvester() { }

    // ----------------------------------------------------------- cultivos

    static boolean isMature(ServerLevel level, BlockPos pos, BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.WHEAT || b == Blocks.CARROTS || b == Blocks.POTATOES || b == Blocks.BEETROOTS) {
            return b instanceof CropBlock crop && crop.isMaxAge(s);
        }
        if (b == Blocks.NETHER_WART) return s.getValue(BlockStateProperties.AGE_3) >= 3;
        if (b == Blocks.COCOA) return s.getValue(BlockStateProperties.AGE_2) >= 2;
        if (b == Blocks.SWEET_BERRY_BUSH) return s.getValue(BlockStateProperties.AGE_3) >= 3;
        if (b == Blocks.MELON) return hasAttachedStem(level, pos, Blocks.ATTACHED_MELON_STEM);
        if (b == Blocks.PUMPKIN) return hasAttachedStem(level, pos, Blocks.ATTACHED_PUMPKIN_STEM);
        return false;
    }

    /** Solo cuentan melones/calabazas pegados a un tallo que apunta hacia ellos (no los decorativos). */
    private static boolean hasAttachedStem(ServerLevel level, BlockPos pos, Block stem) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(dir);
            if (!level.hasChunkAt(n)) continue;
            BlockState st = level.getBlockState(n);
            if (st.is(stem) && st.getValue(BlockStateProperties.HORIZONTAL_FACING) == dir.getOpposite()) {
                return true;
            }
        }
        return false;
    }

    /** Semilla que se gasta al replantar cada cultivo. */
    private static Item seedFor(Block b) {
        if (b == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (b == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        if (b == Blocks.CARROTS) return Items.CARROT;
        if (b == Blocks.POTATOES) return Items.POTATO;
        if (b == Blocks.NETHER_WART) return Items.NETHER_WART;
        if (b == Blocks.COCOA) return Items.COCOA_BEANS;
        return null;
    }

    /**
     * Cosecha y replanta. Devuelve lo obtenido (con la semilla del replantado ya descontada)
     * o null si el cultivo ya no estaba listo.
     */
    static Map<Item, Integer> harvest(ServerLevel level, BlockPos pos, Entity actor) {
        BlockState s = level.getBlockState(pos);
        if (!isMature(level, pos, s)) return null;
        Block b = s.getBlock();

        List<ItemStack> drops = Block.getDrops(s, level, pos, level.getBlockEntity(pos), actor, ItemStack.EMPTY);

        BlockState next;
        Item seed = seedFor(b);
        if (b == Blocks.SWEET_BERRY_BUSH) {
            next = s.setValue(BlockStateProperties.AGE_3, 1);   // se recogen las bayas sin romper el arbusto
        } else if (b == Blocks.COCOA) {
            next = s.setValue(BlockStateProperties.AGE_2, 0);   // conserva la orientacion
        } else if (b == Blocks.MELON || b == Blocks.PUMPKIN) {
            next = Blocks.AIR.defaultBlockState();               // el tallo vuelve a dar fruto
        } else {
            next = b.defaultBlockState();                        // edad 0
        }
        level.setBlock(pos, next, 3);

        Map<Item, Integer> out = new LinkedHashMap<>();
        boolean seedTaken = seed == null;
        for (ItemStack d : drops) {
            if (d.isEmpty()) continue;
            int n = d.getCount();
            if (!seedTaken && d.is(seed)) { // una semilla de lo cosechado se usa para replantar
                n--;
                seedTaken = true;
            }
            if (n > 0) out.merge(d.getItem(), n, Integer::sum);
        }
        return out;
    }

    // ------------------------------------------------------------- escaneo

    /** Escaneo incremental de cultivos listos y cofres alrededor de un centro. */
    static final class Scan {
        private final ServerLevel level;
        private final BlockPos center;
        private final int radius;
        private final int side;
        private int index;
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        final List<BlockPos> crops = new ArrayList<>();
        final List<BlockPos> chests = new ArrayList<>();

        Scan(ServerLevel level, BlockPos center, int radius) {
            this.level = level;
            this.center = center;
            this.radius = radius;
            this.side = 2 * radius + 1;
        }

        /** Procesa hasta "budget" bloques. Devuelve true cuando termino todo el area. */
        boolean step(int budget) {
            int total = side * side;
            long r2 = (long) radius * radius;
            while (index < total && budget > 0) {
                int dx = index % side - radius;
                int dz = index / side - radius;
                index++;
                budget--;
                if ((long) dx * dx + (long) dz * dz > r2) continue;
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (!level.hasChunkAt(m.set(x, center.getY(), z))) continue; // chunk descargado: no se ve
                budget -= 2 * CHEST_VERTICAL + 1;
                for (int y = center.getY() - CHEST_VERTICAL; y <= center.getY() + CHEST_VERTICAL; y++) {
                    m.set(x, y, z);
                    BlockState s = level.getBlockState(m);
                    if (s.isAir()) continue;
                    if (Math.abs(y - center.getY()) <= VERTICAL && isMature(level, m, s)) {
                        crops.add(m.immutable());
                    } else if (s.getBlock() instanceof ChestBlock) {
                        chests.add(m.immutable());
                    }
                }
            }
            return index >= total;
        }
    }
}
