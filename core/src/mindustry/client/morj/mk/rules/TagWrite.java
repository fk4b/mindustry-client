package mindustry.client.morj.mk.rules;

import arc.struct.Seq;
import arc.math.geom.Point2;
import mindustry.client.morj.mk.util.Schematic;
import mindustry.game.Rules;
import mindustry.type.Item;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.world.Block;

public class TagWrite implements AutoCloseable {
    private Rules rules;

    private static final TagWrite write = new TagWrite();

    public static TagWrite of(Rules rules) {
        write.rules = rules;
        return write;
    }

    @Override
    public void close() {
        rules = null;
    }

    public void w(String key, int v) {
        if (rules == null) return;
        rules.tags.put(key, Integer.toString(v));
    }
    public void w(String key, float v) {
        if (rules == null) return;
        rules.tags.put(key, Float.toString(v));
    }
    public void w(String key, boolean v) {
        if (rules == null) return;
        rules.tags.put(key, Boolean.toString(v));
    }
    public void w(String key, Block v) {
        if (rules == null) return;
        rules.tags.put(key, v.name);
    }
    public void w(String key, UnitType v) {
        if (rules == null) return;
        rules.tags.put(key, v.name);
    }
    public void w(String key, Item v) {
        if (rules == null) return;
        rules.tags.put(key, v.name);
    }
    public void w(String key, StatusEffect v) {
        if (rules == null) return;
        rules.tags.put(key, v.name);
    }
    public void w(String key, String v) {
        if (rules == null) return;
        rules.tags.put(key, v);
    }
    public void wPoint(String key, Point2 v) {
        if (rules == null) return;
        rules.tags.put(key, v.x + " " + v.y);
    }
    public void wPoints(String key, Seq<Point2> v) {
        if (rules == null) return;
        if (v == null || v.size == 0) { rules.tags.remove(key); return; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size; i++) {
            if (i > 0) sb.append(',');
            sb.append(v.get(i).x).append(' ').append(v.get(i).y);
        }
        rules.tags.put(key, sb.toString());
    }
    public void w(String key, Schematic v) {
        if (rules == null) return;
        rules.tags.put(key, v.serialize());
    }
}
