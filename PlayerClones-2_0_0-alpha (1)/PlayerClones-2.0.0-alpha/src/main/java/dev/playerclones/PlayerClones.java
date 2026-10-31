package dev.playerclones;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.entity.decoration.Mannequin;

public final class PlayerClones implements ModInitializer {
    public static final String MOD_ID = "playerclones";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CloneCommands.register(dispatcher));

        // Persistencia: la identidad y la tarea de cada clon viven en tags de la propia entidad,
        // asi que al cargar el chunk volvemos a registrarlo y retoma su trabajo.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof Mannequin m) CloneManager.register(m);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> CloneManager.unregister(entity));

        ServerTickEvents.END_SERVER_TICK.register(server -> CloneManager.tick());
    }
}
