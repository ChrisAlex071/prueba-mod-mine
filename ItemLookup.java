package dev.playerclones;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Resuelve items por nombre corto ("iron_ingot") o completo ("minecraft:iron_ingot"). */
final class ItemLookup {
    private static Map<String, Item> byPath;
    private static Map<String, Item> byFull;

    private ItemLookup() {}

    private static void init() {
        if (byPath != null) return;
        Map<String, Item> paths = new TreeMap<>();
        Map<String, Item> fulls = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            var key = BuiltInRegistries.ITEM.getKey(item);
            fulls.put(key.toString(), item);
            String path = key.getPath();
            if (key.getNamespace().equals("minecraft") || !paths.containsKey(path)) {
                paths.put(path, item);
            }
        }
        byPath = paths;
        byFull = fulls;
    }

    static Item byName(String name) {
        if (name == null) return null;
        init();
        return name.contains(":") ? byFull.get(name) : byPath.get(name);
    }

    static Iterable<String> names() {
        init();
        return byPath.keySet();
    }

    static String fullName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    static String shortName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
