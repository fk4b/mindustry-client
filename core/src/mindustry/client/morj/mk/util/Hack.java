package mindustry.client.morj.mk.util;

import arc.func.Cons;
import arc.func.Func;
import arc.func.Prov;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Reflect;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.ctype.Content;
import mindustry.ctype.MappableContent;
import mindustry.mod.Mods;
import mindustry.world.Block;
import mindustry.world.Tile;

import java.util.WeakHashMap;

public class Hack {
    private Hack() {}

    static WeakHashMap<Block, Cons<Tile>> floorRemoved = new WeakHashMap<>();
    public static void floorRemoved(Block block, Cons<Tile> tile) {
        floorRemoved.put(block, tile);
    }
    public static void floorRemoved(Block block, Tile tile) {
        Cons<Tile> cons = floorRemoved.get(block);
        if (cons != null) cons.get(tile);
    }

    static WeakHashMap<Block, Cons<Tile>> blockRemoved = new WeakHashMap<>();
    public static void blockRemoved(Block block, Cons<Tile> tile) {
        blockRemoved.put(block, tile);
    }
    public static void blockRemoved(Block block, Tile tile) {
        Cons<Tile> cons = blockRemoved.get(block);
        if (cons != null) cons.get(tile);
    }

    public static <M extends MappableContent, Orig extends M, New extends M> New replaceContent(Orig original, Func<String, New> newContent) {
        Mods.LoadedMod currentMod = Reflect.get(ContentLoader.class, Vars.content, "currentMod");
        Reflect.set(ContentLoader.class, Vars.content, "currentMod", null);
        ObjectMap<String, MappableContent>[] contentNameMap = Reflect.get(ContentLoader.class, Vars.content, "contentNameMap");
        Seq<Content>[] contentMap = Reflect.get(ContentLoader.class, Vars.content, "contentMap");
        ObjectMap<String, MappableContent> nameeMap = contentNameMap[original.getContentType().ordinal()];
        ObjectMap<String, MappableContent> nameMap = Reflect.get(ContentLoader.class, Vars.content, "nameMap");
        Seq<Content> contentMapp = contentMap[original.getContentType().ordinal()];

        if (!nameeMap.containsKey(original.name)) throw new IllegalArgumentException("Content was not registered!");

        nameeMap.remove(original.name);
        nameMap.remove(original.name);
        int idx = contentMapp.indexOf(original);
        contentMapp.remove(idx);
        if (idx == -1) throw new IllegalStateException("Fuck");
        short id = original.id;

        New content = newContent.get(original.name);
        content.loadIcon();
        content.load();

        contentMapp.remove(content);
        contentMapp.insert(idx, content);
        content.id = id;

        Reflect.set(ContentLoader.class, Vars.content, "currentMod", currentMod);

        return content;
    }
}
