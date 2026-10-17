package mindustry.client.morj.mk.rules;

import arc.math.geom.Point2;
import arc.struct.Seq;
import mindustry.client.morj.mk.util.FormatException;
import mindustry.client.morj.mk.util.Schematic;
import mindustry.Vars;
import mindustry.game.Rules;
import mindustry.type.Item;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.world.Block;

public class TagRead implements AutoCloseable {
    private TagRead() {}

    private Rules rules;
    private final Seq<Rules> prevRules = new Seq<>();

    private static final TagRead read = new TagRead();

    public static TagRead of(Rules rules) {
        if (read.rules != null) read.prevRules.add(read.rules);
        read.rules = rules;
        return read;
    }

    @Override
    public void close() {
        rules = null;
        if (prevRules.size > 0) rules = prevRules.pop();
    }

    public int r(String key, int de) {
        if (rules == null) return de;
        return rules.tags.getInt(key, de);
    }
    public float r(String key, float de) {
        if (rules == null) return de;
        return rules.tags.getFloat(key, de);
    }
    public boolean r(String key, boolean de) {
        if (rules == null) return de;
        String value = rules.tags.get(key);
        if (value == null) return de;
        return value.equals("true");
    }
    public Block r(String key, Block de) {
        if (rules == null) return de;
        String value = rules.tags.get(key);
        if (value == null) return de;
        Block block = Vars.content.block(value);
        if (block == null) return de;
        return block;
    }
    public UnitType r(String key, UnitType de) {
        if (rules == null) return de;
        String value = rules.tags.get(key);
        if (value == null) return de;
        UnitType unit = Vars.content.unit(value);
        if (unit == null) return de;
        return unit;
    }
    public Item r(String key, Item de) {
        if (rules == null) return de;
        String value = rules.tags.get(key);
        if (value == null) return de;
        Item item = Vars.content.item(value);
        if (item == null) return de;
        return item;
    }
    public StatusEffect r(String key, StatusEffect de) {
        if (rules == null) return de;
        String value = rules.tags.get(key);
        if (value == null) return de;
        StatusEffect status = Vars.content.statusEffect(value);
        if (status == null) return de;
        return status;
    }
    public String r(String key, String de) {
        if (rules == null) return de;
        String value = rules.tags.get(key);
        if (value == null) return de;
        return value;
    }
    public Point2 rPoint(String key, Point2 de) {
        if (rules == null) return de;
        String value = rules.tags.get(key);
        if (value == null) return de;
        String[] parts = value.split(" ", 2);
        if (parts.length != 2) return de;
        try {
            return new Point2(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException ignored) {
            return de;
        }
    }
    public Seq<Point2> rPoints(String key, Seq<Point2> de) {
        if (rules == null) return de;
        String value = rules.tags.get(key);
        if (value == null || value.isEmpty()) return de;
        arc.struct.Seq<Point2> result = new arc.struct.Seq<>();
        for (String entry : value.split(",")) {
            String[] parts = entry.trim().split(" ", 2);
            if (parts.length != 2) return de;
            try {
                result.add(new Point2(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
            } catch (NumberFormatException ignored) {
                return de;
            }
        }
        return result.isEmpty() ? de : result;
    }
    public Schematic r(String key, Schematic de) {
        if (rules == null) return de;
        String value = rules.tags.get(key);
        if (value == null) return de;
        try { return Schematic.of(value); }
        catch (FormatException ignored) { return de; }
    }
}
