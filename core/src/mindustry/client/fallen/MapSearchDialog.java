package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.client.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/** Поиск по карте: руда, постройки и юниты любой команды, клик - камера летит туда. */
public class MapSearchDialog extends BaseDialog{
    private static final int maxGroups = 40, maxPositions = 40;
    private static final float highlightTime = 4f * 60f;
    private static MapSearchDialog instance;
    private static boolean hooked;
    private static float hx, hy, hTime = -999f;

    private final Seq<Entry> index = new Seq<>();
    private final Table results = new Table();
    private final ObjectSet<Entry> expanded = new ObjectSet<>();
    private TextField field;
    private String query = "";

    private static class Entry{
        UnlockableContent content;
        @Nullable Team team;
        boolean ore;
        String search;
        final FloatSeq pos = new FloatSeq();
    }

    public static void init(){
        if(hooked) return;
        hooked = true;
        Events.run(Trigger.update, () -> {
            if(state.isGame() && Core.scene.getKeyboardFocus() == null && Core.input.keyTap(Binding.map_search)) open();
        });
        Events.run(Trigger.draw, () -> {
            if(!state.isGame() || Time.time - hTime > highlightTime) return;
            float f = 1f - (Time.time - hTime) / highlightTime;
            Draw.z(Layer.overlayUI);
            Lines.stroke(2f, Tmp.c1.set(Pal.accent).a(f));
            Lines.circle(hx, hy, 10f + Mathf.absin(Time.time, 4f, 6f));
            Lines.circle(hx, hy, 28f * (1f - f) + 6f);
            Draw.reset();
        });
    }

    public static void open(){
        if(!state.isGame()) return;
        if(instance == null) instance = new MapSearchDialog();
        instance.show();
    }

    public MapSearchDialog(){
        super("@client.mapsearch.title");
        addCloseButton();
        shown(() -> {
            rebuildIndex();
            expanded.clear();
            refresh();
            Core.app.post(() -> {
                if(field != null) Core.scene.setKeyboardFocus(field);
            });
        });

        cont.table(t -> {
            t.image(Icon.zoom).padRight(8f);
            field = t.field("", s -> {
                query = s.trim().toLowerCase();
                expanded.clear();
                refresh();
            }).growX().get();
            field.setMessageText(Core.bundle.get("client.mapsearch.hint"));
        }).growX().padBottom(6f).row();
        cont.pane(results).grow().top();
    }

    private void rebuildIndex(){
        index.clear();
        ObjectMap<Object, Entry> map = new ObjectMap<>();

        OreSafety.ensureFresh();
        for(OreSafety.Cluster c : OreSafety.clusters){
            Entry e = map.get(c.item, () -> entry(c.item, null, true));
            e.pos.add(c.x, c.y);
        }
        for(Building b : Groups.build){
            if(b.inFogTo(player.team())) continue;
            Entry e = map.get(key(b.block, b.team), () -> entry(b.block, b.team, false));
            e.pos.add(b.x, b.y);
        }
        for(Unit u : Groups.unit){
            if(u.inFogTo(player.team())) continue;
            Entry e = map.get(key(u.type, u.team), () -> entry(u.type, u.team, false));
            e.pos.add(u.x, u.y);
        }
        index.addAll(map.values().toSeq());
    }

    private static String key(UnlockableContent c, Team t){
        return c.getContentType().name() + ":" + c.id + ":" + t.id;
    }

    private static Entry entry(UnlockableContent c, @Nullable Team team, boolean ore){
        Entry e = new Entry();
        e.content = c;
        e.team = team;
        e.ore = ore;
        e.search = (c.localizedName + " " + c.name + (team != null ? " " + team.localized() + " " + team.name : "") + (ore ? " " + Core.bundle.get("client.mapsearch.ore") : "")).toLowerCase();
        return e;
    }

    private boolean matches(Entry e){
        if(query.isEmpty()) return false;
        for(String word : query.split("\\s+")){
            if(!e.search.contains(word)) return false;
        }
        return true;
    }

    private void refresh(){
        results.clear();
        results.top().left();
        results.defaults().growX().left();
        if(query.isEmpty()){
            results.add("@client.mapsearch.empty").color(Color.lightGray).wrap().pad(10f);
            return;
        }
        Seq<Entry> found = index.select(this::matches);
        Vec2 me = player.unit() != null ? Tmp.v3.set(player.unit()) : Tmp.v3.set(Core.camera.position);
        found.sort(e -> nearest(e, me.x, me.y));
        if(found.isEmpty()){
            results.add("@client.mapsearch.nothing").color(Color.lightGray).pad(10f);
            return;
        }
        int shown = 0;
        for(Entry e : found){
            if(shown++ >= maxGroups) break;
            addGroup(e, me.x, me.y);
        }
    }

    private static float nearest(Entry e, float x, float y){
        float best = Float.MAX_VALUE;
        for(int i = 0; i < e.pos.size; i += 2){
            best = Math.min(best, Mathf.dst2(x, y, e.pos.get(i), e.pos.get(i + 1)));
        }
        return best;
    }

    private void addGroup(Entry e, float mx, float my){
        int count = e.pos.size / 2;
        String teamText = e.team == null ? "" : " [#" + e.team.color + "]" + e.team.localized() + "[]";
        String label = e.content.localizedName + (e.ore ? " [lightgray](" + Core.bundle.get("client.mapsearch.ore") + ")" : "") + teamText
            + " [lightgray]x" + count;

        results.button(b -> {
            b.left();
            b.image(e.content.uiIcon).size(32f).padRight(8f);
            b.add(label).left().growX().wrap();
            b.image(count == 1 ? Icon.rightSmall : expanded.contains(e) ? Icon.upOpenSmall : Icon.downOpenSmall).size(24f);
        }, Styles.flatBordert, () -> {
            if(count == 1){
                go(e.pos.get(0), e.pos.get(1));
            }else{
                if(!expanded.add(e)) expanded.remove(e);
                refresh();
            }
        }).height(48f).padTop(2f).row();

        if(count > 1 && expanded.contains(e)){
            Seq<Vec2> points = new Seq<>();
            for(int i = 0; i < e.pos.size; i += 2) points.add(new Vec2(e.pos.get(i), e.pos.get(i + 1)));
            points.sort(v -> v.dst2(mx, my));
            Table list = new Table();
            list.defaults().left().growX();
            int n = 0;
            for(Vec2 v : points){
                if(n++ >= maxPositions) break;
                int tiles = (int)(v.dst(mx, my) / tilesize);
                String text = "  " + (int)(v.x / tilesize) + ", " + (int)(v.y / tilesize) + "  [lightgray]" + Core.bundle.get("client.mapsearch.tiles").replace("{0}", Integer.toString(tiles));
                list.button(text, Styles.flatt, () -> go(v.x, v.y)).height(34f).padLeft(40f).row();
            }
            results.add(list).row();
        }
    }

    private void go(float x, float y){
        hide();
        if(control.input instanceof DesktopInput d) d.panning = true;
        Spectate.INSTANCE.spectate(new Vec2(x, y), false);
        hx = x;
        hy = y;
        hTime = Time.time;
    }
}
