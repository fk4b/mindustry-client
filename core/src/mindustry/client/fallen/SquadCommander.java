package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.types.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

/**
 * Командир отряда.
 * Включаешь режим, тянешь ЛКМ линию фронта - выделенные юниты (или, если ничего не выделено, все свои боевые
 * юниты на экране) расходятся по ней равномерно и держат позицию: команда "двигаться" сама стреляет по всему в радиусе.
 * Юнит с низким здоровьем уходит к ближайшей ремонтной точке (или к ядру, если её нет), вылечившись - возвращается на своё место.
 * Приказы уходят только при смене состояния юнита, поэтому ручные команды игрока до следующего отступления не перебиваются.
 */
public class SquadCommander{
    private enum State{hold, retreat}

    static class Member{
        final int id;
        float slotX, slotY;
        State state = State.hold;

        Member(int id){
            this.id = id;
        }
    }

    private static final float checkTicks = 30f;
    private static final int sendsPerTick = 4;

    private static boolean hooked, drawing;
    private static Overlay overlay;
    private static BaseDialog settingsDialog;
    private static final FloatSeq line = new FloatSeq(), draft = new FloatSeq();
    private static final Seq<Member> members = new Seq<>();
    //очередь приказов: id, x, y
    private static final FloatSeq orders = new FloatSeq();
    private static float lastCheck;

    public static void init(){
        if(hooked) return;
        hooked = true;

        Events.run(Trigger.update, () -> {
            if(!state.isGame()){
                if(overlay != null || members.any()) disband();
                return;
            }
            if(Core.input.keyTap(Binding.squad_front) && Core.scene.getKeyboardFocus() == null) toggle();
            if(drawing && Core.input.keyDown(KeyCode.mouseLeft)){
                Vec2 m = Core.input.mouseWorld();
                int n = draft.size;
                if(n == 0 || Mathf.dst(draft.items[n - 2], draft.items[n - 1], m.x, m.y) > tilesize) draft.add(m.x, m.y);
            }
            if(members.any() && Time.time - lastCheck >= checkTicks){
                lastCheck = Time.time;
                check();
            }
            flush();
        });

        Events.run(Trigger.draw, () -> {
            if(state.isGame() && (overlay != null || members.any())) draw();
        });

        Events.on(WorldLoadEvent.class, e -> disband());
    }

    public static boolean active(){
        return overlay != null || members.any();
    }

    /** Кнопка панели: режим рисования линии, повторное нажатие - распустить отряд. */
    public static void toggle(){
        if(active()) disband();
        else start();
    }

    private static void start(){
        draft.clear();
        drawing = false;
        overlay = new Overlay();
        Core.scene.add(overlay);
    }

    public static void disband(){
        members.clear();
        orders.clear();
        line.clear();
        draft.clear();
        drawing = false;
        closeOverlay();
    }

    private static void closeOverlay(){
        if(overlay != null){
            Overlay o = overlay;
            overlay = null;
            //вызов может прийти из обработчика оверлея - удалять сразу нельзя
            Core.app.post(o::remove);
        }
    }

    // ---------- линия фронта ----------

    private static void finishLine(){
        drawing = false;
        if(draft.size < 4){
            draft.clear();
            return;
        }
        Seq<Unit> units = pickUnits();
        if(units.isEmpty()){
            ui.showInfoFade(Core.bundle.get("client.squad.nounits"));
            draft.clear();
            return;
        }
        line.clear();
        line.addAll(draft);
        draft.clear();
        assign(units);
        closeOverlay();
        ui.showInfoFade(Core.bundle.format("client.squad.formed", members.size));
    }

    private static Seq<Unit> pickUnits(){
        Seq<Unit> out = new Seq<>();
        for(Unit u : control.input.selectedUnits){
            if(usable(u)) out.add(u);
        }
        if(out.isEmpty()){
            Rect view = Core.camera.bounds(Tmp.r1);
            for(Unit u : player.team().data().units){
                if(usable(u) && u.type.canAttack && view.contains(u.x, u.y)) out.add(u);
            }
        }
        return out;
    }

    private static boolean usable(Unit u){
        return u != null && u.isValid() && !u.dead && u.team == player.team() && !u.isPlayer() && u.controller() instanceof CommandAI;
    }

    /** Места на линии через равные промежутки; юниты по порядку проекции на линию, чтобы не перебегали друг через друга. */
    private static void assign(Seq<Unit> units){
        members.clear();
        orders.clear();
        int n = units.size;
        float total = length();
        float ax = line.items[0], ay = line.items[1];
        float dx = line.items[line.size - 2] - ax, dy = line.items[line.size - 1] - ay;
        units.sort(u -> (u.x - ax) * dx + (u.y - ay) * dy);

        for(int i = 0; i < n; i++){
            Unit u = units.get(i);
            Member m = new Member(u.id);
            pointAt(n == 1 ? total / 2f : total * i / (n - 1f), Tmp.v1);
            m.slotX = Tmp.v1.x;
            m.slotY = Tmp.v1.y;
            members.add(m);
            order(m.id, m.slotX, m.slotY);
        }
    }

    private static float length(){
        float sum = 0f;
        for(int i = 2; i < line.size; i += 2){
            sum += Mathf.dst(line.items[i - 2], line.items[i - 1], line.items[i], line.items[i + 1]);
        }
        return sum;
    }

    private static void pointAt(float dist, Vec2 out){
        for(int i = 2; i < line.size; i += 2){
            float x0 = line.items[i - 2], y0 = line.items[i - 1], x1 = line.items[i], y1 = line.items[i + 1];
            float seg = Mathf.dst(x0, y0, x1, y1);
            if(dist <= seg || i == line.size - 2){
                float f = seg <= 0f ? 0f : Mathf.clamp(dist / seg);
                out.set(Mathf.lerp(x0, x1, f), Mathf.lerp(y0, y1, f));
                return;
            }
            dist -= seg;
        }
        out.set(line.items[0], line.items[1]);
    }

    // ---------- отступление и возврат ----------

    private static void check(){
        float low = Core.settings.getInt("squad-retreat", 40) / 100f, healed = Core.settings.getInt("squad-return", 90) / 100f;
        for(int i = members.size - 1; i >= 0; i--){
            Member m = members.get(i);
            Unit u = Groups.unit.getByID(m.id);
            if(!usable(u)){
                members.remove(i);
                continue;
            }
            float hp = u.health / u.maxHealth;
            if(m.state == State.hold && hp < low){
                Building heal = healPoint(u);
                if(heal == null) continue;
                m.state = State.retreat;
                order(m.id, heal.x, heal.y);
            }else if(m.state == State.retreat && hp >= healed){
                m.state = State.hold;
                order(m.id, m.slotX, m.slotY);
            }
        }
        if(members.isEmpty()){
            ui.showInfoFade(Core.bundle.get("client.squad.gone"));
            disband();
        }
    }

    private static Building healPoint(Unit u){
        Building best = null;
        float bestDst = Float.MAX_VALUE;
        for(Building b : indexer.getFlagged(player.team(), BlockFlag.repair)){
            float d = b.dst2(u);
            if(b.isValid() && d < bestDst){
                bestDst = d;
                best = b;
            }
        }
        return best != null ? best : u.closestCore();
    }

    private static void order(int id, float x, float y){
        orders.add(id, x, y);
    }

    private static void flush(){
        int sent = 0;
        while(orders.size >= 3 && sent < sendsPerTick){
            int id = (int)orders.items[0];
            float x = orders.items[1], y = orders.items[2];
            orders.removeIndex(0);
            orders.removeIndex(0);
            orders.removeIndex(0);
            Unit u = Groups.unit.getByID(id);
            if(!usable(u)) continue;
            Call.commandUnits(player, new int[]{id}, null, null, new Vec2(x, y), false, true);
            sent++;
        }
    }

    // ---------- отрисовка ----------

    private static void draw(){
        float px = Core.camera.width / Math.max(Core.graphics.getWidth(), 1);
        Draw.z(Layer.overlayUI + 0.3f);

        FloatSeq shown = drawing || line.isEmpty() ? draft : line;
        if(shown.size >= 4){
            Lines.stroke(3f * px, Tmp.c1.set(Pal.accent).a(0.8f));
            Lines.beginLine();
            for(int i = 0; i < shown.size; i += 2) Lines.linePoint(shown.items[i], shown.items[i + 1]);
            Lines.endLine();
        }

        int retreating = 0;
        for(Member m : members){
            Unit u = Groups.unit.getByID(m.id);
            if(u == null) continue;
            if(m.state == State.retreat){
                retreating++;
                Lines.stroke(1.5f * px, Tmp.c1.set(Pal.heal).a(0.7f));
                Lines.line(u.x, u.y, m.slotX, m.slotY);
                Draw.color(Pal.heal, 0.8f);
            }else{
                Draw.color(Pal.accent, 0.8f);
            }
            Lines.stroke(1.5f * px);
            Lines.square(m.slotX, m.slotY, 4f * px, 45f);
        }

        if(line.size >= 2 && members.any()){
            Drawf.text(Core.bundle.format("client.squad.label", members.size, retreating), line.items[0], line.items[1] + 16f * px, Color.white, px * 3f);
        }
        Draw.reset();
    }

    public static void showSettings(){
        if(settingsDialog == null){
            settingsDialog = new BaseDialog(Core.bundle.get("client.squad.settings"));
            settingsDialog.addCloseButton();
            settingsDialog.cont.table(t -> {
                t.defaults().left().pad(4);
                slider(t, "squad-retreat", 40, 10, 90, 5, v -> Core.bundle.format("client.squad.set.retreat", v));
                slider(t, "squad-return", 90, 30, 100, 5, v -> Core.bundle.format("client.squad.set.return", v));
                t.add(Core.bundle.get("client.squad.key")).row();
            });
        }
        settingsDialog.show();
    }

    private static void slider(Table t, String key, int def, int min, int max, int step, arc.func.Func<Integer, String> text){
        Label label = new Label("");
        Slider slider = new Slider(min, max, step, false);
        slider.setValue(Core.settings.getInt(key, def));
        slider.changed(() -> {
            Core.settings.put(key, (int)slider.getValue());
            label.setText(text.get((int)slider.getValue()));
        });
        slider.change();
        t.add(label).row();
        t.add(slider).width(320f).row();
    }

    /** Прозрачный слой на весь экран: ЛКМ рисует линию, ПКМ отменяет. */
    private static class Overlay extends Table{
        Overlay(){
            setFillParent(true);
            touchable = Touchable.enabled;
            top();
            Table panel = new Table(Styles.black6);
            panel.margin(8f);
            panel.touchable = Touchable.enabled;
            panel.label(() -> {
                int sel = 0;
                for(Unit u : control.input.selectedUnits) if(usable(u)) sel++;
                return Core.bundle.get("client.squad.hint") + "\n[lightgray]" + (sel > 0 ? Core.bundle.format("client.squad.selected", sel) : Core.bundle.get("client.squad.onscreen"));
            }).padRight(8);
            panel.button(Icon.settingsSmall, Styles.clearNonei, SquadCommander::showSettings).size(36f);
            panel.button(Icon.cancelSmall, Styles.clearNonei, SquadCommander::disband).size(36f);
            add(panel).padTop(40f);

            addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(event.targetActor != Overlay.this) return false;
                    if(button == KeyCode.mouseLeft){
                        draft.clear();
                        drawing = true;
                    }else if(button == KeyCode.mouseRight){
                        Core.app.post(SquadCommander::disband);
                    }
                    return true;
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(button == KeyCode.mouseLeft && drawing) Core.app.post(SquadCommander::finishLine);
                }
            });
        }
    }
}
