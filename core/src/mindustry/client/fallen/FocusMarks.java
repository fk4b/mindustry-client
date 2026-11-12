package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.event.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.client.*;
import mindustry.client.communication.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/**
 * Метки фокуса: клавишей (или кнопкой панели) помечаешь врага, метку видят все игроки с клиентом,
 * а отряды командира отряда переключают огонь на эту цель.
 */
public class FocusMarks{
    private static final float lifetime = 20f * 60f;
    private static boolean hooked;
    private static Table overlay;
    private static BaseDialog settingsDialog;
    private static final Seq<Mark> marks = new Seq<>();
    private static final Rect bounds = new Rect();

    private static class Mark{
        int owner;
        String name;
        Color color;
        float x, y, created;
        @Nullable Teamc target;
    }

    public static void init(){
        if(hooked) return;
        hooked = true;

        Events.run(Trigger.update, () -> {
            if(!state.isGame()){
                marks.clear();
                if(overlay != null) closeOverlay();
                return;
            }
            if(Core.scene.getKeyboardFocus() == null && Core.input.keyTap(Binding.focus_mark)){
                Vec2 m = Core.input.mouseWorld();
                markAt(m.x, m.y);
            }
            marks.removeAll(mk -> {
                if(mk.target != null){
                    if(!mk.target.isAdded() || (mk.target instanceof Healthc h && h.dead())) return true;
                    mk.x = mk.target.getX();
                    mk.y = mk.target.getY();
                }
                return Time.time - mk.created > lifetime;
            });
        });

        Events.run(Trigger.draw, () -> {
            if(state.isGame() && marks.any()) draw();
        });

        Events.on(WorldLoadEvent.class, e -> marks.clear());
    }

    public static boolean hasOwn(){
        return own() != null;
    }

    private static @Nullable Mark own(){
        return marks.find(m -> m.owner == player.id);
    }

    /** Кнопка панели: снять свою метку или выбрать цель кликом. */
    public static void toggle(){
        if(overlay != null){
            closeOverlay();
        }else if(hasOwn()){
            clearOwn(true);
        }else{
            overlay = new Overlay();
            Core.scene.add(overlay);
        }
    }

    private static void closeOverlay(){
        if(overlay != null) overlay.remove();
        overlay = null;
    }

    private static void markAt(float x, float y){
        Teamc target = SquadCommander.targetAt(x, y);
        if(target == null){
            ui.showInfoFade(Core.bundle.get("client.mark.notarget"));
            return;
        }
        Mark old = own();
        if(old != null && old.target == target){
            clearOwn(true);
            return;
        }
        marks.removeAll(m -> m.owner == player.id);
        add(player.id, player.coloredName(), player.color, target, target.getX(), target.getY());

        if(Core.settings.getBool("focusmark-squad", true)) SquadCommander.focusAll(target);
        if(Core.settings.getBool("focusmark-share", true)){
            int kind = target instanceof Unit ? 1 : 2;
            int id = target instanceof Unit u ? u.id : ((Building)target).pos();
            Main.INSTANCE.send(new FocusMarkTransmission(kind, id, target.getX(), target.getY()), null);
        }
    }

    private static void clearOwn(boolean announce){
        marks.removeAll(m -> m.owner == player.id);
        if(Core.settings.getBool("focusmark-squad", true)) SquadCommander.endFocusAll();
        if(announce && Core.settings.getBool("focusmark-share", true)){
            Main.INSTANCE.send(new FocusMarkTransmission(0, 0, 0f, 0f), null);
        }
    }

    private static void add(int owner, String name, Color color, @Nullable Teamc target, float x, float y){
        Mark m = new Mark();
        m.owner = owner;
        m.name = name;
        m.color = color.cpy();
        m.target = target;
        m.x = x;
        m.y = y;
        m.created = Time.time;
        marks.add(m);
    }

    /** Called on the main thread when another client user sends a mark. */
    public static void receive(FocusMarkTransmission t, int senderId){
        if(!state.isGame()) return;
        Player sender = Groups.player.getByID(senderId);
        if(sender == null || sender == player || sender.team() != player.team()) return;
        marks.removeAll(m -> m.owner == senderId);
        if(t.getKind() == 0) return;

        Teamc target = t.getKind() == 1 ? Groups.unit.getByID(t.getTargetId()) : world.build(t.getTargetId());
        if(target != null && target.team() == player.team()) return;
        add(senderId, sender.coloredName(), sender.color, target, t.getX(), t.getY());

        String where = (int)(t.getX() / tilesize) + ", " + (int)(t.getY() / tilesize);
        ui.showInfoFade(Core.bundle.get("client.mark.received").replace("{0}", sender.coloredName()).replace("{1}", where));
        if(target != null && Core.settings.getBool("focusmark-allysquad", false)) SquadCommander.focusAll(target);
    }

    private static void draw(){
        Draw.z(Layer.overlayUI);
        Core.camera.bounds(bounds);
        Rect inner = Tmp.r2.set(bounds).grow(-24f);
        for(Mark m : marks){
            float fade = Mathf.clamp((lifetime - (Time.time - m.created)) / 120f);
            float pulse = Mathf.absin(Time.time, 5f, 3f);
            float size = (m.target instanceof Hitboxc h ? h.hitSize() / 2f : m.target instanceof Building b ? b.hitSize() / 2f : 8f) + 6f + pulse;

            if(bounds.contains(m.x, m.y)){
                Lines.stroke(2f, Tmp.c1.set(Pal.remove).a(fade));
                Lines.circle(m.x, m.y, size);
                for(int i = 0; i < 4; i++){
                    float a = i * 90f + Time.time;
                    Tmp.v1.trns(a, size - 3f);
                    Tmp.v2.trns(a, size + 5f);
                    Lines.line(m.x + Tmp.v1.x, m.y + Tmp.v1.y, m.x + Tmp.v2.x, m.y + Tmp.v2.y);
                }
                text(m.name, m.x, m.y + size + 8f, fade);
            }else{
                //стрелка у края экрана в сторону метки
                float cx = Mathf.clamp(m.x, inner.x, inner.x + inner.width);
                float cy = Mathf.clamp(m.y, inner.y, inner.y + inner.height);
                float angle = Angles.angle(cx, cy, m.x, m.y);
                Draw.color(Tmp.c1.set(Pal.remove).a(fade));
                Fill.poly(cx, cy, 3, 7f + pulse / 2f, angle);
                Draw.color(Tmp.c1.set(m.color).a(fade));
                Fill.circle(cx, cy, 2f);
            }
        }
        Draw.reset();
    }

    private static void text(String s, float x, float y, float alpha){
        Font font = Fonts.outline;
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f / Scl.scl(1f));
        font.setColor(1f, 1f, 1f, alpha);
        font.draw(s, x, y, Align.center);
        font.setUseIntegerPositions(ints);
        font.getData().setScale(1f);
        font.setColor(Color.white);
    }

    public static void showSettings(){
        if(settingsDialog == null){
            settingsDialog = new BaseDialog("@client.mark.settings");
            settingsDialog.cont.defaults().left().pad(4f);
            check("focusmark-squad", true, "@client.mark.set.squad");
            check("focusmark-share", true, "@client.mark.set.share");
            check("focusmark-allysquad", false, "@client.mark.set.allysquad");
            settingsDialog.addCloseButton();
        }
        settingsDialog.show();
    }

    private static void check(String key, boolean def, String text){
        settingsDialog.cont.check(text, Core.settings.getBool(key, def), v -> Core.settings.put(key, v)).row();
    }

    private static class Overlay extends Table{
        Overlay(){
            setFillParent(true);
            touchable = Touchable.enabled;
            top();
            Table panel = new Table(Styles.black6);
            panel.margin(8f);
            panel.touchable = Touchable.enabled;
            panel.add("@client.mark.hint").padRight(8f);
            panel.button(Icon.settingsSmall, Styles.clearNonei, FocusMarks::showSettings).size(36f);
            panel.button(Icon.cancelSmall, Styles.clearNonei, FocusMarks::closeOverlay).size(36f);
            add(panel).padTop(40f);

            addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(event.targetActor != Overlay.this) return false;
                    if(button == KeyCode.mouseLeft){
                        Vec2 m = Core.input.mouseWorld().cpy();
                        Core.app.post(() -> {
                            closeOverlay();
                            markAt(m.x, m.y);
                        });
                    }else if(button == KeyCode.mouseRight){
                        Core.app.post(FocusMarks::closeOverlay);
                    }
                    return true;
                }
            });
        }
    }
}
