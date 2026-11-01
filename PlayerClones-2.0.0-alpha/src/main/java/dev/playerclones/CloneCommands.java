package dev.playerclones;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * /clone spawn [cantidad]
 * /clone list
 * /clone <id> info | stop | remove
 * /clone <id> task stop
 * /clone <id> task trade <dar> <recibir>
 * /clone <id> task sort [radio]
 *
 * Se registra tambien como /pclone: el /clone de vanilla exige permisos de operador y
 * Brigadier fusiona ambos nodos, asi que /pclone funciona siempre (p. ej. sin cheats).
 */
public final class CloneCommands {
    private CloneCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(tree("clone"));
        dispatcher.register(tree("pclone"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree(String root) {
        return Commands.literal(root)
                .then(Commands.literal("spawn")
                        .executes(ctx -> spawn(ctx.getSource(), 1))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, CloneManager.MAX_PER_PLAYER))
                                .executes(ctx -> spawn(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "amount")))))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .then(Commands.literal("info").executes(ctx -> info(ctx.getSource(), id(ctx))))
                        .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource(), id(ctx))))
                        .then(Commands.literal("remove").executes(ctx -> remove(ctx.getSource(), id(ctx))))
                        .then(Commands.literal("task")
                                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource(), id(ctx))))
                                .then(Commands.literal("sort")
                                        .executes(ctx -> sort(ctx.getSource(), id(ctx), 16))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 24))
                                                .executes(ctx -> sort(ctx.getSource(), id(ctx),
                                                        IntegerArgumentType.getInteger(ctx, "radius")))))
                                .then(Commands.literal("trade")
                                        .then(Commands.argument("give", StringArgumentType.word())
                                                .suggests(CloneCommands::suggestItems)
                                                .then(Commands.argument("receive", StringArgumentType.word())
                                                        .suggests(CloneCommands::suggestItems)
                                                        .executes(ctx -> trade(ctx.getSource(), id(ctx),
                                                                StringArgumentType.getString(ctx, "give"),
                                                                StringArgumentType.getString(ctx, "receive"))))))));
    }

    private static int id(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "id");
    }

    private static CompletableFuture<Suggestions> suggestItems(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(ItemLookup.names(), builder);
    }

    // --------------------------------------------------------------- comandos

    private static int spawn(CommandSourceStack src, int amount) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        int owned = CloneManager.countOwned(player.getUUID());
        if (owned + amount > CloneManager.MAX_PER_PLAYER) {
            src.sendFailure(Component.literal("Limite: " + CloneManager.MAX_PER_PLAYER
                    + " clones por jugador (tienes " + owned + ")."));
            return 0;
        }
        List<CloneWorker> created = CloneManager.spawn(player, amount);
        if (created.isEmpty()) {
            src.sendFailure(Component.literal("No se pudo crear el cuerpo del clon."));
            return 0;
        }
        String ids = created.stream().map(w -> "#" + w.id()).collect(Collectors.joining(", "));
        src.sendSuccess(() -> Component.literal("Creado(s): " + ids
                + ". Asigna tarea: /clone <id> task trade <dar> <recibir>"), false);
        return created.size();
    }

    private static int list(CommandSourceStack src) {
        List<CloneWorker> clones = src.getEntity() instanceof ServerPlayer p
                ? CloneManager.ownedBy(p.getUUID())
                : CloneManager.all();
        if (clones.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No tienes clones registrados."), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Clones:"), false);
        for (CloneWorker w : clones) {
            src.sendSuccess(() -> Component.literal("#" + w.id() + " - " + w.describeTask()), false);
        }
        return clones.size();
    }

    private static int info(CommandSourceStack src, int id) {
        CloneWorker w = owned(src, id);
        if (w == null) return 0;
        src.sendSuccess(() -> Component.literal("Clon #" + id + " | " + w.describeFull()), false);
        return 1;
    }

    private static int trade(CommandSourceStack src, int id, String giveName, String receiveName) {
        CloneWorker w = owned(src, id);
        if (w == null) return 0;
        Item give = ItemLookup.byName(giveName);
        Item receive = ItemLookup.byName(receiveName);
        if (give == null) {
            src.sendFailure(Component.literal("Item desconocido: " + giveName));
            return 0;
        }
        if (receive == null) {
            src.sendFailure(Component.literal("Item desconocido: " + receiveName));
            return 0;
        }
        if (give == receive) {
            src.sendFailure(Component.literal("Los dos items no pueden ser el mismo."));
            return 0;
        }
        w.assign(give, receive);
        src.sendSuccess(() -> Component.literal("Clon #" + id + ": sacara " + ItemLookup.shortName(give)
                + " de cofres cercanos, lo cambiara por " + ItemLookup.shortName(receive)
                + " con aldeanos y guardara el resultado en un cofre."), false);
        return 1;
    }

    private static int sort(CommandSourceStack src, int id, int radius) {
        CloneWorker w = owned(src, id);
        if (w == null) return 0;
        w.assignSort(radius);
        src.sendSuccess(() -> Component.literal("Clon #" + id + ": ordenara los cofres a " + radius
                + " bloques a la redonda de donde esta ahora. Un cofre por tipo de item: mueve cada item "
                + "al cofre que ya tiene mas cantidad de el."), false);
        return 1;
    }

    private static int stop(CommandSourceStack src, int id) {
        CloneWorker w = owned(src, id);
        if (w == null) return 0;
        w.clearTask();
        src.sendSuccess(() -> Component.literal("Clon #" + id + " detenido."), false);
        return 1;
    }

    private static int remove(CommandSourceStack src, int id) {
        CloneWorker w = owned(src, id);
        if (w == null) return 0;
        CloneManager.remove(w);
        src.sendSuccess(() -> Component.literal("Clon #" + id + " eliminado (soltó lo que llevaba)."), false);
        return 1;
    }

    /** Devuelve el clon si existe y el ejecutor es su dueño (consola/bloques de comandos pueden todo). */
    private static CloneWorker owned(CommandSourceStack src, int id) {
        CloneWorker w = CloneManager.get(id);
        if (w == null) {
            src.sendFailure(Component.literal("No existe el Clon #" + id
                    + " (o no se pudo cargar su chunk)."));
            return null;
        }
        if (src.getEntity() instanceof ServerPlayer p && !w.owner().equals(p.getUUID())) {
            src.sendFailure(Component.literal("No eres el propietario de este clon."));
            return null;
        }
        return w;
    }
}
