package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/**
 * Радар угроз.
 * Вражеские юниты собираются в группы по сетке. Группа за краем экрана - красная стрелка по краю с иконкой
 * самого крупного юнита, числом юнитов и расстоянием. Атакованные постройки своей команды - пульсирующее кольцо,
 * а за краем экрана - оранжевая стрелка.
 * Группа, подошедшая к ядру или к игроку, даёт звук и строку в чате с координатами (клик ведёт камеру туда).
 * Безопасная копка обходит радиус оружия групп так же, как радиус турелей (OreSafety.isThreatened).
 */
public class ThreatRadar{
    public static boolean enabled = Core.settings.getBool("threatradar", true);

    private static final float cell = 20f * tilesize, attackCell = 10f * tilesize;
    private static final float refreshTicks = 15f, attackMemory = 4f * 60f, alertCooldown = 20f * 60f, globalCooldown = 4f * 60f;

    static class Group{
        float x, y, sumHp, range;
        int count;
        UnitType top;
    }

    static class Attack{
        float x, y, time;
        boolean core;
    }

    private static boolean hooked;
    private static BaseDialog settingsDialog;
    private static final Seq<Group> groups = new Seq<>();
    private static final IntMap<Group> buckets = new IntMap<>();
    private static final IntMap<Attack> attacks = new IntMap<>();
    //ключ ячейки -> время последнего оповещения о ней
    private static final IntFloatMap alerted = new IntFloatMap();
    private static float lastRefresh = -999f, lastAlert = -999f;
    private static final Rect view = new Rect();

    public static void init(){
        if(hooked) return;
        hooked = true;

        Events.run(Trigger.update, () -> {
            if(!enabled || !state.isGame() || player == null) return;
            if(Time.time - lastRefresh >= refreshTicks){
                lastRefresh = Time.time;
                refresh();
            }
        });

        Events.run(Trigger.draw, () -> {
            if(enabled && state.isGame() && player != null) draw();
        });

        Events.on(BuildDamageEvent.class, e -> {
            if(!enabled || e.build == null || player == null || e.build.team != player.team()) return;
            int key = Point2.pack((int)(e.build.x / attackCell), (int)(e.build.y / attackCell));
            Attack a = attacks.get(key);
            boolean fresh = a == null || Time.time - a.time > attackMemory;
            if(a == null) attacks.put(key, a = new Attack());
            a.x = e.build.x;
            a.y = e.build.y;
            a.time = Time.time;
            a.core = e.build.block instanceof mindustry.world.blocks.storage.CoreBlock;
            //о ядре клиент и так пишет сам
            if(fresh && !a.core) alertAttack(key, a);
        });

        Events.on(WorldLoadEvent.class, e -> reset());
    }

    public static void setEnabled(boolean v){
        enabled = v;
        Core.settings.put("threatradar", v);
        if(!v) reset();
    }

    public static void toggle(){
        setEnabled(!enabled);
        new mindustry.client.ui.Toast(1).add(Core.bundle.get("client.morj.btn.radar") + ": " + Core.bundle.get(enabled ? "mod.enabled" : "mod.disabled"));
    }

    public static void showSettings(){
        if(settingsDialog == null){
            settingsDialog = new BaseDialog(Core.bundle.get("client.radar.settings"));
            settingsDialog.addCloseButton();
            settingsDialog.cont.table(t -> {
                t.defaults().left().pad(4);
                check(t, "threatradar-alert", "client.radar.set.alert");
                check(t, "threatradar-attackchat", "client.radar.set.attackchat");
                check(t, "threatradar-sound", "client.radar.set.sound");
                check(t, "threatradar-safemine", "client.radar.set.safemine");
                slider(t, "threatradar-range", 50, 10, 150, 5, v -> Core.bundle.format("client.radar.set.range", v));
                slider(t, "threatradar-mincount", 3, 1, 20, 1, v -> Core.bundle.format("client.radar.set.mincount", v));
                slider(t, "threatradar-margin", 4, 0, 20, 1, v -> Core.bundle.format("client.radar.set.margin", v));
            });
        }
        settingsDialog.show();
    }

    private static void check(arc.scene.ui.layout.Table t, String key, String name){
        t.check(Core.bundle.get(name), Core.settings.getBool(key, true), b -> Core.settings.put(key, b)).row();
    }

    private static void slider(arc.scene.ui.layout.Table t, String key, int def, int min, int max, int step, arc.func.Func<Integer, String> text){
        arc.scene.ui.Label label = new arc.scene.ui.Label("");
        arc.scene.ui.Slider slider = new arc.scene.ui.Slider(min, max, step, false);
        slider.setValue(Core.settings.getInt(key, def));
        slider.changed(() -> {
            Core.settings.put(key, (int)slider.getValue());
            label.setText(text.get((int)slider.getValue()));
        });
        slider.change();
        t.add(label).row();
        t.add(slider).width(320f).row();
    }

    private static void reset(){
        groups.clear();
        buckets.clear();
        attacks.clear();
        alerted.clear();
        lastRefresh = lastAlert = -999f;
    }

    /** Точка в радиусе оружия какой-то вражеской группы. Для безопасной копки. */
    public static boolean isThreatened(float x, float y){
        if(!enabled || !Core.settings.getBool("threatradar-safemine", true)) return false;
        for(Group g : groups){
            if(Mathf.within(x, y, g.x, g.y, g.range)) return true;
        }
        return false;
    }

    private static boolean isEnemy(Team t){
        return t != player.team() && t != Team.derelict;
    }

    private static void refresh(){
        buckets.clear();
        groups.clear();
        for(Unit u : Groups.unit){
            if(!u.isValid() || !isEnemy(u.team) || u.dead) continue;
            int key = Point2.pack((int)(u.x / cell), (int)(u.y / cell));
            Group g = buckets.get(key);
            if(g == null){
                buckets.put(key, g = new Group());
                groups.add(g);
            }
            float hp = Math.max(u.health, 1f);
            g.x += u.x * hp;
            g.y += u.y * hp;
            g.sumHp += hp;
            g.count++;
            g.range = Math.max(g.range, u.range());
            if(g.top == null || u.type.health > g.top.health) g.top = u.type;
        }
        float margin = Core.settings.getInt("threatradar-margin", 4) * tilesize;
        for(Group g : groups){
            g.x /= g.sumHp;
            g.y /= g.sumHp;
            //группа расползается по ячейке: берём радиус оружия плюс разброс
            g.range += cell / 2f + margin;
        }

        for(var it = attacks.values().iterator(); it.hasNext();){
            if(Time.time - it.next().time > attackMemory * 3f) it.remove();
        }

        checkApproach();
    }

    // ---------- оповещения ----------

    private static void checkApproach(){
        if(!Core.settings.getBool("threatradar-alert", true)) return;
        float range = Core.settings.getInt("threatradar-range", 50) * tilesize;
        int minCount = Core.settings.getInt("threatradar-mincount", 3);
        Unit me = player.unit();

        for(Group g : groups){
            if(g.count < minCount) continue;
            var core = player.team().cores().min(c -> c.dst2(g.x, g.y));
            boolean nearCore = core != null && core.within(g.x, g.y, range);
            boolean nearMe = me != null && !me.dead && me.within(g.x, g.y, range * 0.6f);
            if(!nearCore && !nearMe) continue;

            //ключ крупнее ячейки группы, чтобы одна и та же волна не писала на каждом шаге
            int key = Point2.pack((int)(g.x / (cell * 3f)), (int)(g.y / (cell * 3f)));
            if(Time.time - alerted.get(key, -alertCooldown) < alertCooldown || Time.time - lastAlert < globalCooldown) continue;
            alerted.put(key, Time.time);
            lastAlert = Time.time;

            String what = nearCore ? Core.bundle.get("client.radar.tocore") : Core.bundle.get("client.radar.tome");
            chat(Core.bundle.format("client.radar.approach", g.count, g.top.emoji(), what, (int)(g.x / tilesize), (int)(g.y / tilesize)));
        }
    }

    private static void alertAttack(int key, Attack a){
        if(!Core.settings.getBool("threatradar-attackchat", true)) return;
        if(Time.time - alerted.get(-1 - key, -alertCooldown) < alertCooldown || Time.time - lastAlert < globalCooldown) return;
        alerted.put(-1 - key, Time.time);
        lastAlert = Time.time;
        chat(Core.bundle.format("client.radar.attacked", (int)(a.x / tilesize), (int)(a.y / tilesize)));
    }

    private static void chat(String text){
        NetClient.findCoords(ui.chatfrag.addMsg(text));
        if(Core.settings.getBool("threatradar-sound", true)) Sounds.uiNotify.play();
    }

    // ---------- отрисовка ----------

    private static void draw(){
        //размеры стрелок и текста держим постоянными на экране при любом зуме
        float px = Core.camera.width / Math.max(Core.graphics.getWidth(), 1);
        Core.camera.bounds(view);
        float inset = 34f * px;
        Rect edge = Tmp.r2.set(view.x + inset, view.y + inset, view.width - inset * 2f, view.height - inset * 2f);

        Draw.z(Layer.overlayUI + 0.5f);

        for(Group g : groups){
            if(view.contains(g.x, g.y)) continue;
            float size = (10f + Mathf.clamp(Mathf.log(10f, g.sumHp) * 3f, 0f, 14f)) * px;
            arrow(edge, g.x, g.y, size, Pal.remove, g.top, g.count + "  " + (int)(Core.camera.position.dst(g.x, g.y) / tilesize), px);
        }

        float pulse = Mathf.absin(Time.time, 6f, 1f);
        for(Attack a : attacks.values()){
            float age = Time.time - a.time;
            if(age > attackMemory) continue;
            float alpha = 1f - age / attackMemory;
            if(view.contains(a.x, a.y)){
                Lines.stroke(3f * px, Tmp.c1.set(Pal.remove).a(alpha));
                Lines.circle(a.x, a.y, (6f + pulse * 4f) * tilesize / 2f);
            }else{
                arrow(edge, a.x, a.y, 14f * px, Tmp.c3.set(Pal.accent).a(alpha), null, "!", px);
            }
        }
        Draw.reset();
    }

    private static void arrow(Rect edge, float x, float y, float size, Color color, UnitType icon, String text, float px){
        float cx = Core.camera.position.x, cy = Core.camera.position.y;
        float dx = x - cx, dy = y - cy;
        if(Math.abs(dx) < 0.01f && Math.abs(dy) < 0.01f) return;
        //точка на краю прямоугольника по лучу от центра камеры
        float tx = dx == 0 ? Float.MAX_VALUE : (dx > 0 ? edge.x + edge.width - cx : edge.x - cx) / dx;
        float ty = dy == 0 ? Float.MAX_VALUE : (dy > 0 ? edge.y + edge.height - cy : edge.y - cy) / dy;
        float t = Math.min(tx, ty);
        float ex = cx + dx * t, ey = cy + dy * t;
        float angle = Mathf.angle(dx, dy);

        Draw.color(Color.black, color.a * 0.6f);
        Drawf.tri(ex, ey, size + 3f * px, size * 1.6f + 3f * px, angle);
        Draw.color(color);
        Drawf.tri(ex, ey, size, size * 1.6f, angle);

        //подпись внутрь экрана от стрелки
        float lx = ex - Angles.trnsx(angle, size * 1.4f), ly = ey - Angles.trnsy(angle, size * 1.4f);
        if(icon != null){
            Draw.color(Color.white, color.a);
            Draw.rect(icon.uiIcon, lx, ly, 18f * px, 18f * px);
            ly -= 14f * px;
        }
        Drawf.text(text, lx, ly, Tmp.c2.set(Color.white).a(color.a), px * 3f);
        Draw.reset();
    }
}
