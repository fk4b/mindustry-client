package mindustry.client.morj;

import arc.*;
import arc.math.*;
import arc.scene.style.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ctype.*;
import mindustry.gen.*;

import java.lang.reflect.*;

import static mindustry.Vars.*;

/**
 * Saved layout of the morj panel: tabs (built-in and custom), groups inside tabs,
 * which tab and group each button lives in, plus order, visibility, scale, grid cell,
 * hotkey, custom caption and custom icon of every button.
 */
public final class PanelLayout{
    public static final int unordered = 100000;

    /** Keys of the five tabs the client ships with. Their index matches the old numeric tab ids. */
    public static final String[] builtinTabs = {"mine", "build", "view", "fight", "server"};
    private static final String[] builtinTabNames = {
        "client.morj.tab.mine", "client.morj.tab.build", "client.morj.tab.view", "client.morj.tab.fight", "client.morj.tab.server"
    };
    private static final String[] builtinTabIcons = {"icon:production", "icon:hammer", "icon:eye", "icon:units", "icon:host"};
    /** Sections of the "view" tab, turned into regular groups. Title comes from client.morj.<id>. */
    public static final String[] builtinGroups = {"sec.map", "sec.search", "sec.labels", "sec.nets"};

    private static final char US = '\u001f', RS = '\u001e';

    public static final class Tab{
        public final String key;
        /** Custom name, null = built-in name. */
        public String name;
        /** Icon key (see {@link #icon(String)}), null = built-in icon. */
        public String icon;
        public int cols = 4;
        /** How many groups sit side by side. */
        public int groupCols = 1;

        Tab(String key){
            this.key = key;
        }
    }

    public static final class Group{
        public final String id;
        public String tab;
        /** Custom title, null = built-in title. */
        public String title;
        public boolean collapsed;

        Group(String id, String tab){
            this.id = id;
            this.tab = tab;
        }
    }

    public static final class Slot{
        public final String id;
        public final int col, row;

        public Slot(String id, int col, int row){
            this.id = id;
            this.col = col;
            this.row = row;
        }
    }

    private static final ObjectMap<String, Integer> order = new ObjectMap<>();
    private static final ObjectSet<String> hidden = new ObjectSet<>();
    private static final ObjectFloatMap<String> scale = new ObjectFloatMap<>();
    /** "col:row" for a button the player placed by hand (inside its group). */
    private static final ObjectMap<String, String> pos = new ObjectMap<>();
    private static final ObjectMap<String, String> keys = new ObjectMap<>();
    /** "settingName\ttabKey" */
    private static final Seq<String> extras = new Seq<>();
    private static final Seq<Tab> tabs = new Seq<>();
    private static final Seq<Group> groups = new Seq<>();
    /** Button id -> tab key, only when moved away from its home tab. */
    private static final ObjectMap<String, String> assignTab = new ObjectMap<>();
    /** Button id -> group id ("" = explicitly no group). */
    private static final ObjectMap<String, String> assignGroup = new ObjectMap<>();
    private static final ObjectMap<String, String> captions = new ObjectMap<>();
    private static final ObjectMap<String, String> iconKeys = new ObjectMap<>();
    private static final ObjectMap<String, Drawable> iconCache = new ObjectMap<>();
    private static float titleScale = 1f;
    private static boolean loaded;
    private static int batch;

    private PanelLayout(){}

    // ------------------------------------------------------------------ load / save

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
        keys.clear();
        extras.clear();
        tabs.clear();
        groups.clear();
        assignTab.clear();
        assignGroup.clear();
        captions.clear();
        iconKeys.clear();
        titleScale = 1f;

        int i = 0;
        for(String id : split(Core.settings.getString("morj-panel-order", ""))) order.put(id, i++);
        hidden.addAll(split(Core.settings.getString("morj-panel-hide", "")));
        for(String pair : split(Core.settings.getString("morj-panel-size", ""))){
            int eq = pair.indexOf('=');
            if(eq <= 0) continue;
            float value = Strings.parseFloat(pair.substring(eq + 1), 1f);
            if(Float.isNaN(value)) continue;
            scale.put(pair.substring(0, eq), Mathf.clamp(value, 0.6f, 2f));
        }
        for(String pair : split(Core.settings.getString("morj-panel-pos", ""))){
            int eq = pair.indexOf('=');
            if(eq <= 0) continue;
            int[] cell = parseCell(pair.substring(eq + 1));
            if(cell != null) pos.put(pair.substring(0, eq), cell[0] + ":" + cell[1]);
        }
        float storedScale = Core.settings.getFloat("morj-panel-titlescale", 1f);
        if(!Float.isNaN(storedScale)) titleScale = Mathf.clamp(storedScale, 0.7f, 1.6f);
        for(String pair : split(Core.settings.getString("morj-panel-keys", ""))){
            int eq = pair.indexOf('=');
            if(eq <= 0) continue;
            String id = pair.substring(0, eq), key = pair.substring(eq + 1).trim();
            if(!id.isEmpty() && !key.isEmpty()) keys.put(id, key);
        }

        loadTabs();
        loadGroups();

        String rawExtras = Core.settings.getString("morj-panel-extra", "");
        if(rawExtras != null && !rawExtras.isEmpty()){
            for(String rec : rawExtras.split(";")){
                String item = rec.trim();
                int sep = item.lastIndexOf('\t');
                if(sep <= 0) continue;
                String tab = migrateTab(item.substring(sep + 1));
                if(tab != null) extras.add(item.substring(0, sep) + "\t" + tab);
            }
        }
        readMap("morj-panel-assign2", assignTab);
        readMap("morj-panel-group2", assignGroup);
        readMap("morj-panel-caption2", captions);
        readMap("morj-panel-icon2", iconKeys);
        iconCache.clear();
    }

    private static void loadTabs(){
        String raw = Core.settings.getString("morj-panel-tabs2", "");
        if(raw != null && !raw.isEmpty()){
            for(String rec : raw.split(String.valueOf(RS))){
                String[] f = rec.split(String.valueOf(US), -1);
                if(f.length < 5 || f[0].isEmpty() || tab(f[0]) != null) continue;
                Tab tab = new Tab(f[0]);
                tab.name = f[1].isEmpty() ? null : f[1];
                tab.icon = f[2].isEmpty() ? null : f[2];
                tab.cols = Mathf.clamp(Strings.parseInt(f[3], 4), 1, 12);
                tab.groupCols = Mathf.clamp(Strings.parseInt(f[4], 1), 1, 3);
                tabs.add(tab);
            }
        }else{
            //migrate the old fixed five tabs: order and column count
            int[] legacyOrder = {0, 1, 2, 3, 4};
            String rawOrder = Core.settings.getString("morj-panel-taborder", "");
            if(rawOrder != null && !rawOrder.isEmpty()){
                String[] parts = rawOrder.split(",");
                boolean[] seen = new boolean[5];
                int[] next = new int[5];
                boolean ok = parts.length == 5;
                for(int t = 0; ok && t < 5; t++){
                    int value = Strings.parseInt(parts[t].trim(), -1);
                    if(value < 0 || value >= 5 || seen[value]) ok = false;
                    else{
                        seen[value] = true;
                        next[t] = value;
                    }
                }
                if(ok) legacyOrder = next;
            }
            int[] legacyCols = {4, 4, 4, 4, 4};
            String rawCols = Core.settings.getString("morj-panel-cols", "");
            if(rawCols != null && !rawCols.isEmpty()){
                String[] parts = rawCols.split(",");
                for(int t = 0; t < Math.min(5, parts.length); t++) legacyCols[t] = Mathf.clamp(Strings.parseInt(parts[t].trim(), 4), 1, 12);
            }
            for(int index : legacyOrder){
                Tab tab = new Tab(builtinTabs[index]);
                tab.cols = legacyCols[index];
                if(index == 2) tab.groupCols = 2; //the view tab used two columns of sections
                tabs.add(tab);
            }
        }
        //a built-in tab can never disappear
        for(int t = 0; t < builtinTabs.length; t++){
            if(tab(builtinTabs[t]) == null){
                Tab tab = new Tab(builtinTabs[t]);
                if(t == 2) tab.groupCols = 2;
                tabs.add(tab);
            }
        }
    }

    private static void loadGroups(){
        String raw = Core.settings.getString("morj-panel-groups2", null);
        if(raw != null){
            if(raw.isEmpty()) return;
            for(String rec : raw.split(String.valueOf(RS))){
                String[] f = rec.split(String.valueOf(US), -1);
                if(f.length < 4 || f[0].isEmpty() || tab(f[1]) == null || group(f[0]) != null) continue;
                Group group = new Group(f[0], f[1]);
                group.title = f[2].isEmpty() ? null : f[2];
                group.collapsed = "1".equals(f[3]);
                groups.add(group);
            }
        }else{
            for(String id : builtinGroups) groups.add(new Group(id, "view"));
        }
    }

    /** Old saves stored the tab as 0..4. */
    private static String migrateTab(String raw){
        if(raw == null || raw.isEmpty()) return null;
        int index = Strings.parseInt(raw, -1);
        if(index >= 0 && index < builtinTabs.length) return builtinTabs[index];
        return tab(raw) != null ? raw : builtinTabs[0];
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
        StringBuilder keyBuf = new StringBuilder();
        for(ObjectMap.Entry<String, String> entry : keys.entries()){
            if(keyBuf.length() > 0) keyBuf.append(',');
            keyBuf.append(entry.key).append('=').append(entry.value);
        }
        StringBuilder extraBuf = new StringBuilder();
        for(String rec : extras){
            if(extraBuf.length() > 0) extraBuf.append(';');
            extraBuf.append(rec);
        }
        StringBuilder tabBuf = new StringBuilder();
        for(Tab tab : tabs){
            if(tabBuf.length() > 0) tabBuf.append(RS);
            tabBuf.append(tab.key).append(US).append(clean(tab.name)).append(US).append(clean(tab.icon))
                .append(US).append(tab.cols).append(US).append(tab.groupCols);
        }
        StringBuilder groupBuf = new StringBuilder();
        for(Group group : groups){
            if(groupBuf.length() > 0) groupBuf.append(RS);
            groupBuf.append(group.id).append(US).append(group.tab).append(US).append(clean(group.title))
                .append(US).append(group.collapsed ? "1" : "0");
        }
        Core.settings.put("morj-panel-hide", hide.toString());
        Core.settings.put("morj-panel-order", ord.toString());
        Core.settings.put("morj-panel-size", sizes.toString());
        Core.settings.put("morj-panel-pos", cells.toString());
        Core.settings.put("morj-panel-titlescale", titleScale);
        Core.settings.put("morj-panel-keys", keyBuf.toString());
        Core.settings.put("morj-panel-extra", extraBuf.toString());
        Core.settings.put("morj-panel-tabs2", tabBuf.toString());
        Core.settings.put("morj-panel-groups2", groupBuf.toString());
        writeMap("morj-panel-assign2", assignTab);
        writeMap("morj-panel-group2", assignGroup);
        writeMap("morj-panel-caption2", captions);
        writeMap("morj-panel-icon2", iconKeys);
    }

    /** Every setting this class owns, for a full reset. */
    public static void resetAll(){
        for(String key : new String[]{
            "morj-panel-hide", "morj-panel-order", "morj-panel-size", "morj-panel-pos", "morj-panel-cols", "morj-panel-taborder",
            "morj-panel-titlescale", "morj-panel-keys", "morj-panel-extra", "morj-panel-tabs2", "morj-panel-groups2",
            "morj-panel-assign2", "morj-panel-group2", "morj-panel-caption2", "morj-panel-icon2"
        }){
            Core.settings.remove(key);
        }
        reload();
    }

    private static String clean(String text){
        if(text == null) return "";
        return text.replace(US, ' ').replace(RS, ' ');
    }

    private static void readMap(String setting, ObjectMap<String, String> into){
        String raw = Core.settings.getString(setting, "");
        if(raw == null || raw.isEmpty()) return;
        for(String rec : raw.split(String.valueOf(RS))){
            int sep = rec.indexOf(US);
            if(sep <= 0) continue;
            into.put(rec.substring(0, sep), rec.substring(sep + 1));
        }
    }

    private static void writeMap(String setting, ObjectMap<String, String> map){
        StringBuilder out = new StringBuilder();
        for(ObjectMap.Entry<String, String> entry : map.entries()){
            if(out.length() > 0) out.append(RS);
            out.append(clean(entry.key)).append(US).append(clean(entry.value));
        }
        Core.settings.put(setting, out.toString());
    }

    // ------------------------------------------------------------------ tabs

    public static Seq<Tab> tabs(){
        load();
        return tabs;
    }

    public static Tab tab(String key){
        if(key == null) return null;
        for(Tab tab : tabs) if(tab.key.equals(key)) return tab;
        return null;
    }

    public static boolean builtinTab(String key){
        for(String b : builtinTabs) if(b.equals(key)) return true;
        return false;
    }

    private static int builtinIndex(String key){
        for(int i = 0; i < builtinTabs.length; i++) if(builtinTabs[i].equals(key)) return i;
        return -1;
    }

    public static String tabName(Tab tab){
        if(tab == null) return "";
        if(tab.name != null && !tab.name.isEmpty()) return tab.name;
        int index = builtinIndex(tab.key);
        return index >= 0 ? Core.bundle.get(builtinTabNames[index]) : tab.key;
    }

    public static Drawable tabIcon(Tab tab){
        if(tab == null) return Icon.settingsSmall;
        Drawable custom = icon(tab.icon);
        if(custom != null) return custom;
        int index = builtinIndex(tab.key);
        Drawable builtin = index >= 0 ? icon(builtinTabIcons[index]) : null;
        return builtin != null ? builtin : Icon.folder;
    }

    /** Key of the first tab, used when a saved key no longer exists. */
    public static String firstTab(){
        load();
        return tabs.isEmpty() ? builtinTabs[0] : tabs.first().key;
    }

    public static String addTab(String name){
        load();
        String key = "t" + Long.toString(Time.millis(), 36);
        while(tab(key) != null) key = key + "x";
        Tab tab = new Tab(key);
        tab.name = name == null || name.trim().isEmpty() ? Core.bundle.get("client.morj.panel.newtab") : name.trim();
        tab.icon = "icon:star";
        tabs.add(tab);
        changed();
        return key;
    }

    /** Custom tabs only. Its buttons go back home, its groups are removed. */
    public static void removeTab(String key){
        load();
        Tab tab = tab(key);
        if(tab == null || builtinTab(key)) return;
        begin();
        tabs.remove(tab, true);
        Seq<String> moved = new Seq<>();
        for(ObjectMap.Entry<String, String> entry : assignTab.entries()) if(key.equals(entry.value)) moved.add(entry.key);
        for(String id : moved){
            assignTab.remove(id);
            assignGroup.remove(id);
            pos.remove(id);
        }
        for(int i = groups.size - 1; i >= 0; i--) if(key.equals(groups.get(i).tab)) removeGroupRaw(groups.get(i).id);
        for(int i = 0; i < extras.size; i++){
            String rec = extras.get(i);
            int sep = rec.lastIndexOf('\t');
            if(sep > 0 && rec.substring(sep + 1).equals(key)) extras.set(i, rec.substring(0, sep) + "\t" + builtinTabs[0]);
        }
        end();
    }

    public static void renameTab(String key, String name){
        Tab tab = tab(key);
        if(tab == null) return;
        tab.name = name == null || name.trim().isEmpty() ? null : name.trim();
        changed();
    }

    public static void setTabIcon(String key, String iconKey){
        Tab tab = tab(key);
        if(tab == null) return;
        tab.icon = iconKey;
        changed();
    }

    /** Moves a tab `dir` places in the bar. */
    public static void shiftTab(String key, int dir){
        load();
        Tab tab = tab(key);
        if(tab == null) return;
        int from = tabs.indexOf(tab, true), to = from + dir;
        if(to < 0 || to >= tabs.size) return;
        tabs.swap(from, to);
        changed();
    }

    public static int cols(String key){
        load();
        Tab tab = tab(key);
        return tab == null ? 4 : tab.cols;
    }

    public static void setGroupCols(String key, int value){
        Tab tab = tab(key);
        if(tab == null) return;
        tab.groupCols = Mathf.clamp(value, 1, 3);
        changed();
    }

    // ------------------------------------------------------------------ groups

    public static Seq<Group> groups(String tabKey){
        load();
        Seq<Group> out = new Seq<>();
        for(Group group : groups) if(group.tab.equals(tabKey)) out.add(group);
        return out;
    }

    public static Group group(String id){
        if(id == null || id.isEmpty()) return null;
        for(Group group : groups) if(group.id.equals(id)) return group;
        return null;
    }

    public static String groupTitle(Group group){
        if(group == null) return Core.bundle.get("client.morj.panel.nogroup");
        if(group.title != null && !group.title.isEmpty()) return group.title;
        String key = "client.morj." + group.id;
        return Core.bundle.has(key) ? Core.bundle.get(key) : group.id;
    }

    public static String addGroup(String tabKey, String title){
        load();
        if(tab(tabKey) == null) return null;
        String id = "g" + Long.toString(Time.millis(), 36);
        while(group(id) != null) id = id + "x";
        Group group = new Group(id, tabKey);
        group.title = title == null || title.trim().isEmpty() ? Core.bundle.get("client.morj.panel.newgroup") : title.trim();
        groups.add(group);
        changed();
        return id;
    }

    public static void removeGroup(String id){
        load();
        begin();
        removeGroupRaw(id);
        end();
    }

    private static void removeGroupRaw(String id){
        Group group = group(id);
        if(group == null) return;
        groups.remove(group, true);
        Seq<String> touched = new Seq<>();
        for(ObjectMap.Entry<String, String> entry : assignGroup.entries()) if(id.equals(entry.value)) touched.add(entry.key);
        for(String button : touched){
            assignGroup.put(button, "");
            pos.remove(button);
        }
        //buttons that sat in this built-in group by default now stay ungrouped
        changed();
    }

    public static void renameGroup(String id, String title){
        Group group = group(id);
        if(group == null) return;
        group.title = title == null || title.trim().isEmpty() ? null : title.trim();
        changed();
    }

    public static void setCollapsed(String id, boolean collapsed){
        Group group = group(id);
        if(group == null) return;
        group.collapsed = collapsed;
        changed();
    }

    /** Moves a group `dir` places among the groups of its tab. */
    public static void shiftGroup(String id, int dir){
        Group group = group(id);
        if(group == null) return;
        Seq<Group> same = groups(group.tab);
        int at = same.indexOf(group, true), to = at + dir;
        if(to < 0 || to >= same.size) return;
        Group other = same.get(to);
        groups.swap(groups.indexOf(group, true), groups.indexOf(other, true));
        changed();
    }

    // ------------------------------------------------------------------ buttons: placement

    /** Tab a button is shown on. */
    public static String tabOf(String id, String homeTab){
        load();
        String assigned = assignTab.get(id);
        if(assigned != null && tab(assigned) != null) return assigned;
        return tab(homeTab) != null ? homeTab : firstTab();
    }

    /** Group a button is shown in, "" for none. */
    public static String groupOf(String id, String homeTab, String homeGroup){
        load();
        String tab = tabOf(id, homeTab);
        String assigned = assignGroup.get(id);
        if(assigned != null){
            if(assigned.isEmpty()) return "";
            Group group = group(assigned);
            return group != null && group.tab.equals(tab) ? assigned : "";
        }
        if(homeGroup == null || !tab.equals(homeTab)) return "";
        Group group = group(homeGroup);
        return group != null && group.tab.equals(tab) ? homeGroup : "";
    }

    /** Moves a button to another tab, ungrouped, at the first free cell. */
    public static void moveToTab(String id, String tabKey, String homeTab){
        load();
        if(tab(tabKey) == null) return;
        begin();
        if(tabKey.equals(homeTab)) assignTab.remove(id);
        else assignTab.put(id, tabKey);
        assignGroup.put(id, "");
        pos.remove(id);
        end();
    }

    /** Puts a button into a group of its current tab ("" = no group). */
    public static void moveToGroup(String id, String groupId){
        load();
        begin();
        assignGroup.put(id, groupId == null ? "" : groupId);
        pos.remove(id);
        end();
    }

    // ------------------------------------------------------------------ buttons: looks

    public static String caption(String id){
        load();
        return captions.get(id);
    }

    public static void setCaption(String id, String text){
        load();
        if(text == null || text.trim().isEmpty()) captions.remove(id);
        else captions.put(id, text.trim());
        changed();
    }

    public static String iconKey(String id){
        load();
        return iconKeys.get(id);
    }

    public static void setIcon(String id, String key){
        load();
        if(key == null || key.isEmpty()) iconKeys.remove(id);
        else iconKeys.put(id, key);
        changed();
    }

    /**
     * Resolves an icon key: "icon:name" (an {@link Icon} field), or "item:", "block:", "unit:", "liquid:"
     * followed by a content name. Null when the key is empty or unknown.
     */
    public static Drawable icon(String key){
        if(key == null || key.isEmpty()) return null;
        Drawable cached = iconCache.get(key);
        if(cached != null) return cached;
        Drawable out = null;
        int sep = key.indexOf(':');
        if(sep > 0){
            String type = key.substring(0, sep), name = key.substring(sep + 1);
            try{
                switch(type){
                    case "icon" -> {
                        Field field = Icon.class.getField(name);
                        Object value = field.get(null);
                        if(value instanceof Drawable d) out = d;
                    }
                    case "item" -> out = content(content.item(name));
                    case "block" -> out = content(content.block(name));
                    case "unit" -> out = content(content.unit(name));
                    case "liquid" -> out = content(content.liquid(name));
                    default -> {}
                }
            }catch(Throwable ignored){
                out = null;
            }
        }
        if(out != null) iconCache.put(key, out);
        return out;
    }

    private static Drawable content(UnlockableContent c){
        if(c == null || c.uiIcon == null || !c.uiIcon.found()) return null;
        return new TextureRegionDrawable(c.uiIcon);
    }

    /** All {@link Icon} fields that hold a drawable, for the icon picker. */
    public static Seq<String> iconNames(){
        Seq<String> out = new Seq<>();
        for(Field field : Icon.class.getFields()){
            if(!Modifier.isStatic(field.getModifiers()) || !Drawable.class.isAssignableFrom(field.getType())) continue;
            if(field.getName().endsWith("Small")) continue;
            out.add(field.getName());
        }
        out.sort();
        return out;
    }

    // ------------------------------------------------------------------ order, visibility, size, grid

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
        boolean custom = false;
        for(String id : orderedIds){
            if(!pos.containsKey(id)){
                loose.add(id);
                continue;
            }
            int[] cell = parseCell(pos.get(id));
            if(cell == null){
                loose.add(id);
                continue;
            }
            custom = true;
            String key = cell[0] + ":" + cell[1];
            if(taken.containsKey(key)) loose.add(id);
            else{
                taken.put(key, id);
                maxC = Math.max(maxC, cell[0]);
            }
        }
        Seq<Slot> out = new Seq<>();
        if(!custom){
            for(int n = 0; n < orderedIds.size; n++) out.add(new Slot(orderedIds.get(n), n % width, n / width));
            return out;
        }
        int c = 0, r = 0;
        for(String id : loose){
            while(taken.containsKey(c + ":" + r)){
                c++;
                if(c > maxC){
                    c = 0;
                    r++;
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

    /**
     * Places a button at a cell of a group. `groupIds` are the visible buttons already in that group
     * (the moved one may or may not be among them). A filled target cell swaps.
     */
    public static void place(Seq<String> groupIds, int columns, String id, int toCol, int toRow){
        load();
        toCol = Mathf.clamp(toCol, 0, 16);
        toRow = Mathf.clamp(toRow, 0, 24);
        Seq<String> ids = new Seq<>(groupIds);
        boolean inside = ids.contains(id, false);
        Seq<Slot> slots = resolve(ids, columns);
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
        if(inside && fromC == toCol && fromR == toRow) return;
        begin();
        for(Slot slot : slots) pos.put(slot.id, slot.col + ":" + slot.row);
        if(occupant != null){
            if(inside) pos.put(occupant, fromC + ":" + fromR);
            else pos.remove(occupant); //lands on the first free cell
        }
        pos.put(id, toCol + ":" + toRow);
        end();
    }

    /** Lines a tab's visible buttons into a fresh grid of the given width. */
    public static void reflow(Seq<String> tabIds, String tabKey, int columns){
        load();
        Tab tab = tab(tabKey);
        if(tab == null) return;
        begin();
        tab.cols = Mathf.clamp(columns, 1, 12);
        for(String id : tabIds) pos.remove(id);
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

    public static float titleScale(){
        load();
        return titleScale;
    }

    public static void setTitleScale(float value){
        load();
        titleScale = Mathf.clamp(Mathf.round(value * 20f) / 20f, 0.7f, 1.6f);
        changed();
    }

    public static void setOrder(Seq<String> ids){
        load();
        order.clear();
        for(int n = 0; n < ids.size; n++) order.put(ids.get(n), n);
        changed();
    }

    // ------------------------------------------------------------------ hotkeys

    public static String keyOf(String id){
        load();
        return keys.get(id);
    }

    public static ObjectMap<String, String> keyMap(){
        load();
        return keys;
    }

    /** One key runs one button. Passing null or "unset" clears it. */
    public static void setKey(String id, String keyName){
        load();
        if(id == null || id.isEmpty()) return;
        if(keyName == null || keyName.isEmpty() || "unset".equals(keyName)){
            keys.remove(id);
        }else{
            Seq<String> drop = new Seq<>();
            for(ObjectMap.Entry<String, String> entry : keys.entries()){
                if(keyName.equals(entry.value) && !entry.key.equals(id)) drop.add(entry.key);
            }
            for(String old : drop) keys.remove(old);
            keys.put(id, keyName);
        }
        changed();
    }

    // ------------------------------------------------------------------ pinned settings

    public static Seq<String> extras(){
        load();
        return extras;
    }

    public static void addExtra(String setting, String tabKey){
        load();
        if(setting == null || setting.isEmpty()) return;
        if(tab(tabKey) == null) tabKey = firstTab();
        String prefix = setting + "\t";
        for(String rec : extras) if(rec.startsWith(prefix)) return;
        extras.add(prefix + tabKey);
        changed();
    }

    public static void removeExtra(String setting){
        load();
        if(setting == null) return;
        boolean any = false;
        for(int i = extras.size - 1; i >= 0; i--){
            if(extras.get(i).startsWith(setting + "\t")){
                extras.remove(i);
                any = true;
            }
        }
        if(any) changed();
    }

    // ------------------------------------------------------------------ util

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
