package mindustry.client.morj;

import arc.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;

/** Saved order, visibility, scale and grid cell of the morj panel buttons. */
public final class PanelLayout{
    public static final int unordered = 100000;
    private static final ObjectMap<String, Integer> order = new ObjectMap<>();
    private static final ObjectSet<String> hidden = new ObjectSet<>();
    private static final ObjectFloatMap<String> scale = new ObjectFloatMap<>();
    /** "col:row" for a button the player placed by hand. */
    private static final ObjectMap<String, String> pos = new ObjectMap<>();
    private static final int[] cols = {4, 4, 4, 4, 4};
    private static boolean loaded;
    private static int batch;

    private PanelLayout(){}

    public static final class Slot{
        public final String id;
        public final int col, row;

        public Slot(String id, int col, int row){
            this.id = id;
            this.col = col;
            this.row = row;
        }
    }

    public static void reload(){
        loaded = false;
        load();
    }

    public static void load(){
        if(loaded) return;
        loaded = true;
        order.clear();
        hidden.clear();
        scale.clear();
        pos.clear();
        int i = 0;
        for(String id : split(Core.settings.getString("morj-panel-order", ""))){
            order.put(id, i++);
        }
        hidden.addAll(split(Core.settings.getString("morj-panel-hide", "")));
        for(String pair : split(Core.settings.getString("morj-panel-size", ""))){
            int eq = pair.indexOf('=');
            if(eq <= 0) continue;
            String id = pair.substring(0, eq);
            float value = Strings.parseFloat(pair.substring(eq + 1), 1f);
            if(Float.isNaN(value)) continue;
            scale.put(id, Mathf.clamp(value, 0.6f, 2f));
        }
        for(String pair : split(Core.settings.getString("morj-panel-pos", ""))){
            int eq = pair.indexOf('=');
            if(eq <= 0) continue;
            String id = pair.substring(0, eq);
            int[] cell = parseCell(pair.substring(eq + 1));
            if(cell != null) pos.put(id, cell[0] + ":" + cell[1]);
        }
        String rawCols = Core.settings.getString("morj-panel-cols", "");
        for(int t = 0; t < cols.length; t++) cols[t] = 4;
        if(rawCols != null && !rawCols.isEmpty()){
            String[] parts = rawCols.split(",");
            for(int t = 0; t < Math.min(cols.length, parts.length); t++){
                cols[t] = Mathf.clamp(Strings.parseInt(parts[t].trim(), 4), 1, 12);
            }
        }
    }

    public static boolean hidden(String id){
        load();
        return hidden.contains(id);
    }

    public static float scale(String id){
        load();
        return scale.get(id, 1f);
    }

    public static int index(String id){
        load();
        return order.get(id, unordered);
    }

    public static int cols(int tab){
        load();
        if(tab < 0 || tab >= cols.length) return 4;
        return cols[tab];
    }

    public static boolean hasPos(String id){
        load();
        return pos.containsKey(id);
    }

    /** Visible buttons in list order, with saved cells winning over the plain grid. */
    public static Seq<Slot> resolve(Seq<String> orderedIds, int columns){
        load();
        int width = Math.max(1, columns);
        ObjectMap<String, String> taken = new ObjectMap<>();
        Seq<String> loose = new Seq<>();
        int maxC = width - 1;
        int maxR = 0;
        boolean custom = false;
        for(String id : orderedIds){
            if(!pos.containsKey(id)){
                loose.add(id);
                continue;
            }
            custom = true;
            int[] cell = parseCell(pos.get(id));
            if(cell == null){
                loose.add(id);
                continue;
            }
            String key = cell[0] + ":" + cell[1];
            if(taken.containsKey(key)) loose.add(id);
            else{
                taken.put(key, id);
                maxC = Math.max(maxC, cell[0]);
                maxR = Math.max(maxR, cell[1]);
            }
        }
        Seq<Slot> out = new Seq<>();
        if(!custom){
            for(int n = 0; n < orderedIds.size; n++){
                out.add(new Slot(orderedIds.get(n), n % width, n / width));
            }
            return out;
        }
        int c = 0, r = 0;
        for(String id : loose){
            while(taken.containsKey(c + ":" + r)){
                c++;
                if(c > maxC){
                    c = 0;
                    r++;
                    maxR = Math.max(maxR, r);
                }
            }
            taken.put(c + ":" + r, id);
            c++;
            if(c > maxC){
                c = 0;
                r++;
            }
        }
        for(ObjectMap.Entry<String, String> entry : taken.entries()){
            int[] cell = parseCell(entry.key);
            if(cell != null) out.add(new Slot(entry.value, cell[0], cell[1]));
        }
        return out;
    }

    /** Writes the current grid, then moves one button. A filled target cell swaps. */
    public static void relocate(Seq<String> orderedVisible, int columns, String id, int toCol, int toRow){
        load();
        toCol = Mathf.clamp(toCol, 0, 16);
        toRow = Mathf.clamp(toRow, 0, 24);
        Seq<Slot> slots = resolve(orderedVisible, columns);
        int fromC = -1, fromR = -1;
        String occupant = null;
        for(Slot slot : slots){
            if(slot.id.equals(id)){
                fromC = slot.col;
                fromR = slot.row;
            }else if(slot.col == toCol && slot.row == toRow){
                occupant = slot.id;
            }
        }
        if(fromC < 0 || (fromC == toCol && fromR == toRow)) return;
        begin();
        for(Slot slot : slots) pos.put(slot.id, slot.col + ":" + slot.row);
        if(occupant != null) pos.put(occupant, fromC + ":" + fromR);
        pos.put(id, toCol + ":" + toRow);
        end();
    }

    /** Lines this tab's visible buttons into a fresh grid of the given width. */
    public static void reflow(Seq<String> visualRowMajor, Seq<String> hiddenIds, int tab, int columns){
        load();
        if(tab < 0 || tab >= cols.length) return;
        begin();
        cols[tab] = Mathf.clamp(columns, 1, 12);
        if(hiddenIds != null){
            for(String id : hiddenIds) pos.remove(id);
        }
        int width = cols[tab];
        for(int n = 0; n < visualRowMajor.size; n++){
            pos.put(visualRowMajor.get(n), (n % width) + ":" + (n / width));
        }
        end();
    }

    public static void setHidden(String id, boolean hide){
        load();
        if(hide) hidden.add(id);
        else hidden.remove(id);
        changed();
    }

    public static void setScale(String id, float value){
        load();
        float next = Mathf.clamp(Mathf.round(value * 5f) / 5f, 0.6f, 2f);
        if(Mathf.equal(next, 1f)) scale.remove(id, 0f);
        else scale.put(id, next);
        changed();
    }

    public static void setOrder(Seq<String> ids){
        load();
        order.clear();
        for(int n = 0; n < ids.size; n++){
            order.put(ids.get(n), n);
        }
        changed();
    }

    private static void begin(){
        batch++;
    }

    private static void end(){
        batch = Math.max(0, batch - 1);
        if(batch == 0) save();
    }

    private static void changed(){
        if(batch == 0) save();
    }

    private static void save(){
        StringBuilder hide = new StringBuilder();
        for(String id : hidden){
            if(hide.length() > 0) hide.append(',');
            hide.append(id);
        }
        StringBuilder ord = new StringBuilder();
        Seq<String> ids = new Seq<>();
        for(String id : order.keys()) ids.add(id);
        ids.sort(Structs.comparingInt(id -> order.get(id, unordered)));
        for(String id : ids){
            if(ord.length() > 0) ord.append(',');
            ord.append(id);
        }
        StringBuilder sizes = new StringBuilder();
        scale.each(entry -> {
            if(sizes.length() > 0) sizes.append(',');
            sizes.append(entry.key).append('=').append(entry.value);
        });
        StringBuilder cells = new StringBuilder();
        for(ObjectMap.Entry<String, String> entry : pos.entries()){
            if(cells.length() > 0) cells.append(',');
            cells.append(entry.key).append('=').append(entry.value);
        }
        StringBuilder widths = new StringBuilder();
        for(int t = 0; t < cols.length; t++){
            if(t > 0) widths.append(',');
            widths.append(cols[t]);
        }
        Core.settings.put("morj-panel-hide", hide.toString());
        Core.settings.put("morj-panel-order", ord.toString());
        Core.settings.put("morj-panel-size", sizes.toString());
        Core.settings.put("morj-panel-pos", cells.toString());
        Core.settings.put("morj-panel-cols", widths.toString());
    }

    private static int[] parseCell(String raw){
        if(raw == null) return null;
        int colon = raw.indexOf(':');
        if(colon <= 0 || colon >= raw.length() - 1) return null;
        int c = Strings.parseInt(raw.substring(0, colon), -1);
        int r = Strings.parseInt(raw.substring(colon + 1), -1);
        if(c < 0 || r < 0 || c > 16 || r > 24) return null;
        return new int[]{c, r};
    }

    private static Seq<String> split(String raw){
        Seq<String> out = new Seq<>();
        if(raw == null || raw.isEmpty()) return out;
        for(String part : raw.split(",")){
            String id = part.trim();
            if(!id.isEmpty()) out.add(id);
        }
        return out;
    }
}
