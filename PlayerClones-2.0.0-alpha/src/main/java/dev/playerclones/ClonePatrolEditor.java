package dev.playerclones;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;

/** Editor de rutas con azada de madera; solo muestra marcadores al propietario seleccionado. */
final class ClonePatrolEditor {
    private static final Map<UUID, UUID> SELECTED = new HashMap<>();
    private static int ticks;

    static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (hand != InteractionHand.MAIN_HAND || !player.getItemInHand(hand).is(Items.WOODEN_HOE))
                return InteractionResult.PASS;
            if (!(entity instanceof Mannequin)) return InteractionResult.PASS;
            if (world.isClientSide()) return InteractionResult.SUCCESS;
            CloneWorker worker = workerByBody(entity.getUUID());
            if (worker == null) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (!worker.owner().equals(sp.getUUID())) {
                sp.sendSystemMessage(Component.literal("Este clon no te pertenece."));
                return InteractionResult.SUCCESS;
            }
            UUID selected = SELECTED.get(sp.getUUID());
            if (worker.uuid().equals(selected)) {
                SELECTED.remove(sp.getUUID());
                sp.sendSystemMessage(Component.literal("Clon #" + worker.id() + " deseleccionado."));
            } else {
                SELECTED.put(sp.getUUID(), worker.uuid());
                sp.sendSystemMessage(Component.literal("Clon #" + worker.id()
                        + " seleccionado. Clic derecho sobre bloques para añadir o quitar puntos (max. 64)."));
            }
            return InteractionResult.SUCCESS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (hand != InteractionHand.MAIN_HAND || !player.getItemInHand(hand).is(Items.WOODEN_HOE))
                return InteractionResult.PASS;
            if (world.isClientSide()) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            CloneWorker worker = selected(sp);
            if (worker == null) return InteractionResult.PASS;
            if (worker.serverLevel() != world) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos().above();
            int result = worker.toggleWaypoint(pos);
            if (result == 0) sp.sendSystemMessage(Component.literal("Limite de 64 puntos por ruta."));
            else if (result < 0) sp.sendSystemMessage(Component.literal("Punto " + (-result) + " eliminado."));
            else sp.sendSystemMessage(Component.literal("Punto " + result + " añadido."));
            return InteractionResult.SUCCESS;
        });
    }

    static void tick(net.minecraft.server.MinecraftServer server) {
        if (++ticks % 10 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.getMainHandItem().is(Items.WOODEN_HOE)) continue;
            CloneWorker worker = selected(player);
            if (worker == null || worker.serverLevel() != player.level()) continue;
            ServerLevel level = worker.serverLevel();
            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getLookAngle();
            int lookingAt = -1;
            double best = Double.MAX_VALUE;
            int index = 0;
            for (BlockPos pos : worker.route()) {
                Vec3 center = Vec3.atBottomCenterOf(pos);
                if (eye.distanceToSqr(center) > 48 * 48) { index++; continue; }
                // Punto visual encima del bloque seleccionado, solo para este jugador.
                level.sendParticles(player, ParticleTypes.END_ROD, false, false,
                        center.x, center.y + 0.1, center.z, 2, 0.18, 0.14, 0.18, 0.005);
                Vec3 diff = center.subtract(eye);
                double along = diff.dot(look);
                if (along > 0 && along < 8) {
                    double perpendicular = diff.subtract(look.scale(along)).lengthSqr();
                    if (perpendicular < 0.7 && perpendicular < best) {
                        best = perpendicular;
                        lookingAt = index;
                    }
                }
                index++;
            }
            if (lookingAt >= 0) player.sendSystemMessage(
                    Component.literal("Punto de patrulla #" + (lookingAt + 1)), true);
        }
    }

    private static CloneWorker selected(ServerPlayer player) {
        UUID id = SELECTED.get(player.getUUID());
        if (id == null) return null;
        CloneWorker worker = workerByBody(id);
        if (worker == null || !worker.owner().equals(player.getUUID())) SELECTED.remove(player.getUUID());
        return worker;
    }

    private static CloneWorker workerByBody(UUID id) {
        for (CloneWorker worker : CloneManager.all()) if (worker.uuid().equals(id)) return worker;
        return null;
    }

    private ClonePatrolEditor() { }
}
