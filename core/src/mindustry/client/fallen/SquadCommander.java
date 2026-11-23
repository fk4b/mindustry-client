package mindustry.client.fallen;

import arc.*;
import arc.func.Boolp;
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
import mindustry.ai.*;
import mindustry.ai.types.*;
import mindustry.content.UnitTypes;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

/**
 * Командир отряда: до трёх отрядов, у каждого своя линия фронта, строй, тактика и пороги здоровья.
 * Тянешь ЛКМ линию - выделенные юниты (или, если ничего не выделено, все свои боевые на экране) расходятся по ней и держат позицию.
 * Раненый уходит к свободной ремонтной точке безопасным путём и возвращается на своё место; новые юниты встают в отряд сами.
 * Все приказы идут через одну очередь с лимитом (см. sendIntervalMs), поэтому никакая функция не может устроить кик за пакеты.
 */
public class SquadCommander{
    private enum State{hold, retreat}
    private enum Shape{line, wedge, arc, ring}
    private enum Tactic{hold, advance, cautious}
    private enum Tool{draw, move, focus, advance}

    static class Member{
        final int id;
        float slotX, slotY;
        State state = State.hold;
        int healPos = -1;
        //заполняются на время расстановки
        float ux, uy, range;
        boolean healer;
        int rank;
        /** Право прохода: юнит временно стоит в стороне от движущегося раненого. */
        boolean yielding;
        int yieldFor = -1;
        float yieldX, yieldY;
        /** Точка, где юнит стоял до того, как начал уступать дорогу. */
        float yieldFromX, yieldFromY;
        /** Юнит держит огонь, ожидая залпа. */
        boolean waitingForVolley;
        boolean holdFireForVolley;
        /** Группа залпа по цели, к которой привязан юнит в текущем тике. */
        @Nullable VolleyGroup volleyGroup;

        Member(int id){
            this.id = id;
        }
    }

    /** Залп синхронизируется по типу и по общей/близкой цели. */
    static class VolleyGroup{
        UnitType type;
        @Nullable Teamc target;
        float timer = -1f;
        boolean done;
        boolean assigned;
        boolean allInRange;
        boolean defend;
    }

    static class Squad{
        final int index;
        final Seq<Member> members = new Seq<>();
        final FloatSeq line = new FloatSeq();
        final ObjectSet<UnitType> types = new ObjectSet<>();
        final IntSet focusIds = new IntSet();
        final Seq<VolleyGroup> volleys = new Seq<>();
        Shape shape = Shape.line;
        Tactic tactic = Tactic.hold;
        boolean retreatAll;
        @Nullable Teamc focus;
        /** Цель фокуса выбрана автоосадой, а не игроком: её можно сменить на более важную. */
        boolean autoFocus;
        float advanceX, advanceY, fallX, fallY, lastLayout, lastStep, hp, maxHp;
        int laidOut, lost, healing;

        Squad(int index){
            this.index = index;
        }

        void clear(){
            members.clear();
            line.clear();
            types.clear();
            focusIds.clear();
            shape = Shape.line;
            tactic = Tactic.hold;
            retreatAll = false;
            focus = null;
            autoFocus = false;
            fallX = fallY = hp = maxHp = 0f;
            laidOut = lost = healing = 0;
            volleys.clear();
        }

        int retreatPercent(){
            return Core.settings.getInt("squad" + index + "-retreat", Core.settings.getInt("squad-retreat", 40));
        }

        int returnPercent(){
            return Core.settings.getInt("squad" + index + "-return", Core.settings.getInt("squad-return", 90));
        }
    }

    /** Приказ в одну точку или по одной цели. Id целые: во float id больше 2^24 округляется, и приказ уходит не тому юниту. */
    private static class Order{
        final float x, y;
        final @Nullable Teamc target;
        final IntSeq ids = new IntSeq();

        Order(float x, float y, @Nullable Teamc target){
            this.x = x;
            this.y = y;
            this.target = target;
        }
    }

    private static final float checkTicks = 30f;
    /**
     * Сервер отключает и заносит IP в чёрный список за 300 пакетов за 3 с (packetSpamLimit), считая все пакеты соединения.
     * Один приказ на 50 мс - 60 пакетов за 3 с при любом FPS, с запасом на синхронизацию (~45) и копку (до 20).
     */
    private static final long sendIntervalMs = 50L;
    /** Больше мест на линии не делаем: большой отряд стоит группами, и один пакет ведёт всю группу. */
    private static final int maxSlots = 30;
    /** Столько же кладёт в пакет обычный приказ игры. */
    private static final int maxIdsPerPacket = 200;
    private static final int squadCount = 3;
    private static final float rankGap = 7f * tilesize, healLoadPenalty = 8f * tilesize;
    private static final float layoutCooldown = 5f * 60f, stepCooldown = 4f * 60f, fallCooldown = 5f * 60f;
    /** Автоосада: важные постройки ищем в этом радиусе от линии, прочие (стены и т.п.) - только вплотную к ней. */
    private static final float siegeRadius = 20f * tilesize, obstacleRadius = 8f * tilesize;
    private static final float advanceStep = 8f * tilesize, fallStep = 12f * tilesize, enemyRadius = 32f * tilesize, contactRadius = 25f * tilesize, coreKeepOut = 15f * tilesize;
    /** Интервал проверки залпа - чаще, чем check(), для точности синхронизации. */
    private static final float volleyTicks = 10f;
    /** Задержка залпа по умолчанию (кадров) после выхода всей группы на дистанцию. */
    private static final int defaultVolleyDelay = 15;
    /** Радиус объединения близких целей в один залп по умолчанию (блоков/тайлов). */
    private static final int defaultVolleyTargetRadius = 10;

    private static boolean hooked, drawing, dragging;
    private static Overlay overlay;
    private static Tool tool = Tool.draw;
    private static BaseDialog settingsDialog;
    private static Table hud;
    private static final Squad[] squads = new Squad[squadCount];
    private static int current;
    private static final FloatSeq draft = new FloatSeq();
    private static final IntSeq picked = new IntSeq();
    private static final IntSet known = new IntSet();
    private static final IntIntMap healLoad = new IntIntMap();
    private static final Seq<Order> queue = new Seq<>();
    private static float lastCheck, lastVolleyCheck;
    private static long nextSend;
    private static final Vec2 sideSpot = new Vec2();
    //перетаскивание в режиме сдвига
    private static float dragStartX, dragStartY, previewX, previewY, previewAngle;
    private static boolean rotating;

    static{
        for(int i = 0; i < squadCount; i++) squads[i] = new Squad(i);
    }

    public static void init(){
        if(hooked) return;
        hooked = true;

        Events.run(Trigger.update, () -> {
            if(!state.isGame()){
                if(active()) disbandAll();
                return;
            }
            if(hud == null) buildHud();
            if(hud.parent == null) Core.scene.add(hud);

            if(Core.scene.getKeyboardFocus() == null){
                if(Core.input.keyTap(Binding.squad_front)) toggle();
                if(Core.input.keyTap(Binding.squad_1)) select(0);
                if(Core.input.keyTap(Binding.squad_2)) select(1);
                if(Core.input.keyTap(Binding.squad_3)) select(2);
                if(Core.input.keyTap(Binding.squad_hud_move)) toggleHudMove();
            }
            updateTools();
            if(anySquad() && Time.time - lastCheck >= checkTicks){
                lastCheck = Time.time;
                rebuildHealLoad();
                reinforce();
                for(Squad s : squads) if(s.members.any()) check(s);
            }
            if(anySquad() && Time.time - lastVolleyCheck >= volleyTicks){
                lastVolleyCheck = Time.time;
                syncVolleys();
            }
            flush();
        });

        Events.run(Trigger.draw, () -> {
            if(state.isGame() && active()) draw();
        });

        Events.on(WorldLoadEvent.class, e -> disbandAll());
    }

    private static Squad cur(){
        return squads[current];
    }

    private static boolean anySquad(){
        for(Squad s : squads) if(s.members.any()) return true;
        return false;
    }

    public static boolean active(){
        return overlay != null || anySquad();
    }

    /** Кнопка панели: режим рисования линии, повторное нажатие - распустить текущий отряд. */
    public static void toggle(){
        if(overlay != null) closeOverlay();
        else if(cur().members.any()) disband();
        else openTool(Tool.draw);
    }

    /** Выбрать отряд; у пустого сразу открывается рисование линии. */
    private static void select(int index){
        current = index;
        if(cur().members.isEmpty()) openTool(Tool.draw);
        else closeOverlay();
    }

    private static void openTool(Tool t){
        closeOverlayNow();
        tool = t;
        if(t == Tool.draw){
            //выделение запоминается при включении: пока рисуешь линию, игра может его сбросить
            picked.clear();
            for(Unit u : control.input.selectedUnits) if(usable(u)) picked.add(u.id);
            draft.clear();
            drawing = false;
        }
        dragging = false;
        overlay = new Overlay();
        Core.scene.add(overlay);
        if(hud != null) hud.toFront();
    }

    public static void disband(){
        Squad s = cur();
        dropOrders(s);
        resetMembers(s);
        s.clear();
        if(overlay != null && tool != Tool.draw) closeOverlay();
    }

    private static void disbandAll(){
        for(Squad s : squads){
            resetMembers(s);
            s.clear();
        }
        queue.clear();
        known.clear();
        draft.clear();
        drawing = false;
        dragging = false;
        closeOverlay();
    }

    private static void dropOrders(Squad s){
        for(Member m : s.members) unqueue(m.id);
    }

    /** Снять с юнитов клиентские настройки отряда при роспуске. */
    private static void resetMembers(Squad s){
        for(Member m : s.members){
            Unit u = Groups.unit.getByID(m.id);
            if(u != null && u.controller() instanceof CommandAI ai){
                ai.targetBlocksOnly = false;
                if(m.holdFireForVolley) ai.setStance(UnitStance.holdFire, false);
            }
        }
    }

    private static void closeOverlay(){
        if(overlay != null){
            Overlay o = overlay;
            overlay = null;
            //вызов может прийти из обработчика оверлея - удалять сразу нельзя
            Core.app.post(o::remove);
        }
        drawing = false;
        dragging = false;
    }

    private static void closeOverlayNow(){
        if(overlay != null) closeOverlay();
    }

    // ---------- инструменты на карте ----------

    private static void updateTools(){
        if(overlay == null) return;
        Vec2 m = Core.input.mouseWorld();
        if(tool == Tool.draw && drawing && Core.input.keyDown(KeyCode.mouseLeft)){
            int n = draft.size;
            if(n == 0 || Mathf.dst(draft.items[n - 2], draft.items[n - 1], m.x, m.y) > tilesize) draft.add(m.x, m.y);
        }else if(tool == Tool.move && dragging){
            if(rotating){
                Vec2 c = center(cur(), Tmp.v2);
                previewAngle = Angles.angle(c.x, c.y, m.x, m.y) - Angles.angle(c.x, c.y, dragStartX, dragStartY);
            }else{
                previewX = m.x - dragStartX;
                previewY = m.y - dragStartY;
            }
        }
    }

    private static void finishLine(){
        drawing = false;
        if(draft.size < 4){
            draft.clear();
            return;
        }
        Squad s = cur();
        Seq<Unit> units = pickUnits(s);
        if(units.isEmpty()){
            ui.showInfoFade(Core.bundle.get("client.squad.nounits"));
            draft.clear();
            return;
        }
        s.line.clear();
        s.line.addAll(draft);
        s.fallX = s.fallY = 0f;
        draft.clear();
        form(s, units);
        closeOverlay();
        ui.showInfoFade(Core.bundle.format("client.squad.formed", s.members.size));
    }

    private static void commitMove(){
        dragging = false;
        Squad s = cur();
        if(s.members.isEmpty()) return;
        if(rotating){
            if(!Mathf.zero(previewAngle)) rotate(s, previewAngle);
        }else if(previewX != 0f || previewY != 0f){
            translate(s, previewX, previewY);
        }
        previewX = previewY = previewAngle = 0f;
        closeOverlay();
    }

    private static void pickFocus(){
        Vec2 m = Core.input.mouseWorld();
        Teamc t = targetAt(m.x, m.y);
        if(t == null){
            ui.showInfoFade(Core.bundle.get("client.squad.notarget"));
            return;
        }
        setFocus(cur(), t, Core.input.keyDown(KeyCode.shiftLeft), false);
        closeOverlay();
    }

    private static void pickAdvance(){
        Vec2 m = Core.input.mouseWorld();
        Squad s = cur();
        s.advanceX = m.x;
        s.advanceY = m.y;
        s.tactic = Tactic.advance;
        s.lastStep = -999f;
        closeOverlay();
    }

    static @Nullable Teamc targetAt(float x, float y){
        Unit best = null;
        float bestDst = Float.MAX_VALUE;
        for(Unit e : Groups.unit){
            if(!e.isValid() || e.dead || !hostile(e.team) || e.inFogTo(player.team())) continue;
            float d = e.dst(x, y) - e.hitSize / 2f;
            if(d < tilesize * 1.5f && d < bestDst){
                bestDst = d;
                best = e;
            }
        }
        if(best != null) return best;
        Building b = world.buildWorld(x, y);
        return b != null && hostile(b.team) && !b.inFogTo(player.team()) ? b : null;
    }

    private static boolean hostile(Team t){
        return t != player.team() && t != Team.derelict;
    }

    // ---------- формирование ----------

    private static Seq<Unit> pickUnits(Squad s){
        Seq<Unit> out = new Seq<>();
        for(Unit u : control.input.selectedUnits){
            if(usable(u)) out.add(u);
        }
        if(out.isEmpty()){
            for(int i = 0; i < picked.size; i++){
                Unit u = Groups.unit.getByID(picked.get(i));
                if(usable(u)) out.add(u);
            }
        }
        //перерисовка линии без выделения оставляет прежний состав
        if(out.isEmpty()){
            for(Member m : s.members){
                Unit u = Groups.unit.getByID(m.id);
                if(usable(u)) out.add(u);
            }
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

    private static void form(Squad s, Seq<Unit> units){
        IntSet ids = new IntSet();
        for(Unit u : units) ids.add(u.id);
        //юнит может быть только в одном отряде
        for(Squad other : squads){
            if(other == s || other.members.isEmpty()) continue;
            other.members.removeAll(m -> ids.contains(m.id));
            if(other.members.isEmpty()) other.clear();
        }

        Seq<Member> old = new Seq<>(s.members);
        if(old.isEmpty()) s.lost = 0;
        s.members.clear();
        s.types.clear();
        for(Unit u : units){
            Member m = old.find(o -> o.id == u.id);
            s.members.add(m != null ? m : new Member(u.id));
            s.types.add(u.type);
        }
        for(Unit u : player.team().data().units) known.add(u.id);
        layout(s, true);
    }

    /** Новая расстановка: задние ряды по дальности, внутри ряда порядок по линии, чтобы юниты не перебегали друг через друга. */
    private static void layout(Squad s, boolean orderAll){
        Seq<Member> live = new Seq<>();
        float minRange = Float.MAX_VALUE;
        for(Member m : s.members){
            Unit u = Groups.unit.getByID(m.id);
            if(u == null) continue;
            m.ux = u.x;
            m.uy = u.y;
            m.range = u.range();
            m.healer = u.type.canHeal;
            if(!m.healer) minRange = Math.min(minRange, m.range);
            live.add(m);
        }
        if(live.isEmpty()) return;

        boolean ranks = Core.settings.getBool("squad-ranks", true);
        boolean fighters = minRange != Float.MAX_VALUE;
        int[] counts = new int[3];
        for(Member m : live){
            m.rank = !ranks ? 0 : m.healer && fighters ? 2 : !m.healer && m.range > minRange * 1.3f ? 1 : 0;
            counts[m.rank]++;
        }

        Vec2 fw = forward(s, new Vec2());
        Vec2 c = center(s, new Vec2());
        Vec2 p = new Vec2();
        float ax = s.line.items[0], ay = s.line.items[1];
        float dx = s.line.items[s.line.size - 2] - ax, dy = s.line.items[s.line.size - 1] - ay;
        int shown = 0;

        for(int r = 0; r < 3; r++){
            int rank = r, n = counts[r];
            if(n == 0) continue;
            float offset = shown++ * rankGap;
            Seq<Member> row = live.select(m -> m.rank == rank);
            if(s.shape == Shape.ring) row.sort(m -> Angles.angle(c.x, c.y, m.ux, m.uy));
            else row.sort(m -> (m.ux - ax) * dx + (m.uy - ay) * dy);

            int quota = Math.min(n, Math.max(1, Math.round(maxSlots * n / (float)live.size)));
            for(int k = 0; k < n; k++){
                Member m = row.get(k);
                int slot = k * quota / n;
                float f = s.shape == Shape.ring ? slot / (float)quota : quota == 1 ? 0.5f : slot / (quota - 1f);
                slotPoint(s, f, offset, fw, c, p);
                boolean moved = Mathf.dst(m.slotX, m.slotY, p.x, p.y) > tilesize * 2f;
                m.slotX = p.x;
                m.slotY = p.y;
                if((orderAll || moved) && holding(s, m)) order(m.id, m.slotX, m.slotY, null);
            }
        }
        s.laidOut = live.size;
        s.lastLayout = Time.time;
    }

    /** Юнит сейчас стоит на линии и слушает приказы линии: не лечится, не бьёт цель фокуса, не уступает дорогу. */
    private static boolean holding(Squad s, Member m){
        //юнита, которого UnitClearLogic уводит со стройки, не тянем обратно в слот
        return m.state == State.hold && !m.yielding && !s.retreatAll && !s.focusIds.contains(m.id) && !UnitClearLogic.isClearing(m.id);
    }

    private static void slotPoint(Squad s, float f, float rankOffset, Vec2 fw, Vec2 c, Vec2 out){
        float total = length(s);
        float bulge = 0f;
        switch(s.shape){
            case ring -> {
                float ax = s.line.items[0], ay = s.line.items[1];
                float bx = s.line.items[s.line.size - 2], by = s.line.items[s.line.size - 1];
                float radius = Math.max(Mathf.dst(ax, ay, bx, by) / 2f, total / Mathf.PI2);
                out.trns(f * 360f, Math.max(radius - rankOffset, tilesize * 2f)).add(c);
                return;
            }
            case wedge -> bulge = total * 0.35f * (1f - Math.abs(2f * f - 1f));
            case arc -> bulge = total * 0.25f * Mathf.sin(f * Mathf.PI);
            default -> {}
        }
        pointAt(s, total * f, out);
        out.add(fw.x * (bulge - rankOffset), fw.y * (bulge - rankOffset));
    }

    private static Vec2 center(Squad s, Vec2 out){
        if(s.line.size < 4) return out.setZero();
        return out.set((s.line.items[0] + s.line.items[s.line.size - 2]) / 2f, (s.line.items[1] + s.line.items[s.line.size - 1]) / 2f);
    }

    /** Единичный вектор «вперёд» от линии: к ближайшему вражескому ядру, а если его нет - от своего. */
    private static Vec2 forward(Squad s, Vec2 out){
        float dx = s.line.items[s.line.size - 2] - s.line.items[0], dy = s.line.items[s.line.size - 1] - s.line.items[1];
        if(Mathf.zero(dx) && Mathf.zero(dy)) return out.set(0f, 1f);
        out.set(-dy, dx).nor();
        Vec2 c = center(s, Tmp.v3);
        Building enemy = state.teams.closestEnemyCore(c.x, c.y, player.team());
        Building own = player.team().core();
        float sign = 1f;
        if(enemy != null) sign = (enemy.x - c.x) * out.x + (enemy.y - c.y) * out.y;
        else if(own != null) sign = (c.x - own.x) * out.x + (c.y - own.y) * out.y;
        return sign < 0f ? out.scl(-1f) : out;
    }

    private static float length(Squad s){
        float sum = 0f;
        FloatSeq line = s.line;
        for(int i = 2; i < line.size; i += 2){
            sum += Mathf.dst(line.items[i - 2], line.items[i - 1], line.items[i], line.items[i + 1]);
        }
        return sum;
    }

    private static void pointAt(Squad s, float dist, Vec2 out){
        FloatSeq line = s.line;
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

    private static void translate(Squad s, float dx, float dy){
        for(int i = 0; i < s.line.size; i += 2){
            s.line.items[i] += dx;
            s.line.items[i + 1] += dy;
        }
        for(Member m : s.members){
            m.slotX += dx;
            m.slotY += dy;
            if(holding(s, m)) order(m.id, m.slotX, m.slotY, null);
        }
    }

    private static void rotate(Squad s, float degrees){
        Vec2 c = center(s, new Vec2());
        Vec2 p = new Vec2();
        for(int i = 0; i < s.line.size; i += 2){
            p.set(s.line.items[i] - c.x, s.line.items[i + 1] - c.y).rotate(degrees).add(c);
            s.line.items[i] = p.x;
            s.line.items[i + 1] = p.y;
        }
        for(Member m : s.members){
            p.set(m.slotX - c.x, m.slotY - c.y).rotate(degrees).add(c);
            m.slotX = p.x;
            m.slotY = p.y;
            if(holding(s, m)) order(m.id, m.slotX, m.slotY, null);
        }
    }

    // ---------- обход отрядов ----------

    private static void check(Squad s){
        float low = s.retreatPercent() / 100f, healed = s.returnPercent() / 100f;
        boolean blocksOnly = Core.settings.getBool("squad-blocksonly", false);
        String myName = player == null ? "" : Strings.stripColors(player.name);
        float hp = 0f, maxHp = 0f;
        int healing = 0;
        for(int i = s.members.size - 1; i >= 0; i--){
            Member m = s.members.get(i);
            Unit u = Groups.unit.getByID(m.id);
            if(!usable(u)){
                if(u == null || u.dead) s.lost++;
                if(u != null && u.controller() instanceof CommandAI ai){
                    ai.targetBlocksOnly = false;
                    if(m.holdFireForVolley) ai.setStance(UnitStance.holdFire, false);
                }
                s.focusIds.remove(m.id);
                s.members.remove(i);
                continue;
            }
            hp += u.health;
            maxHp += u.maxHealth;
            float ratio = u.health / u.maxHealth;

            // Опция "атаковать только постройки": игнорируем вражеских юнитов, но не мешаем игроку.
            if(!u.isPlayer() && u.controller() instanceof CommandAI ai && ai.targetBlocksOnly != blocksOnly){
                ai.targetBlocksOnly = blocksOnly;
                if(blocksOnly){
                    if(ai.attackTarget instanceof Unit) ai.attackTarget = null;
                    ai.targetInvalidated();
                }
            }

            if(!s.retreatAll){
                if(m.state == State.hold && ratio < low){
                    retreat(s, m, u);
                    releaseVolley(u, m);
                }else if(m.state == State.retreat && ratio >= healed){
                    comeBack(s, m, u);
                    releaseVolley(u, m);
                }
            }
            if(m.state == State.retreat && healGone(m)) retreat(s, m, u);

            // Право прохода: если раненый уже прошёл, юнит возвращается на своё место.
            if(m.yielding && !yieldStillNeeded(m, u)){
                m.yielding = false;
                m.yieldFor = -1;
                m.yieldX = m.yieldY = m.yieldFromX = m.yieldFromY = 0f;
                if(m.state == State.hold && !s.retreatAll){
                    if(s.focusIds.contains(m.id) && s.focus != null){
                        order(m.id, s.focus.getX(), s.focus.getY(), s.focus);
                    }else{
                        order(m.id, m.slotX, m.slotY, null);
                    }
                }
            }

            // Чужой приказ (другой игрок/скрипт) перебиваем своим, пока юнит не ранен.
            if(!u.isPlayer() && !m.yielding){
                String cmdr = u.lastCommanded != null ? Strings.stripColors(u.lastCommanded) : "";
                if(!cmdr.isEmpty() && !cmdr.equals(myName)){
                    if(m.state == State.retreat && m.healPos != -1){
                        Building heal = world.build(m.healPos);
                        if(heal != null){
                            Vec2 spot = healSpot(u, heal, new Vec2());
                            order(m.id, spot.x, spot.y, null);
                        }
                    }else if(m.state == State.hold && !s.retreatAll){
                        if(s.focusIds.contains(m.id) && s.focus != null){
                            order(m.id, s.focus.getX(), s.focus.getY(), s.focus);
                        }else{
                            order(m.id, m.slotX, m.slotY, null);
                        }
                    }
                }
            }

            if(m.state == State.retreat) healing++;
        }
        s.hp = hp;
        s.maxHp = maxHp;
        s.healing = healing;

        yieldRightOfWay();

        if(s.members.isEmpty()){
            ui.showInfoFade(Core.bundle.get("client.squad.gone"));
            s.clear();
            return;
        }

        if(s.focus != null && !targetAlive(s.focus)) endFocus(s);
        siege(s);

        boolean changed = s.members.size != s.laidOut && Math.abs(s.members.size - s.laidOut) * 10 >= s.laidOut;
        if(changed && Time.time - s.lastLayout >= layoutCooldown && Core.settings.getBool("squad-closegaps", true)) layout(s, false);

        if(s.tactic == Tactic.advance) advance(s);
        else if(s.tactic == Tactic.cautious) cautious(s);
    }

    private static boolean targetAlive(Teamc t){
        return t instanceof Unit u ? u.isValid() && !u.dead : t instanceof Building b && b.isValid();
    }

    private static void advance(Squad s){
        if(Time.time - s.lastStep < stepCooldown || s.retreatAll || s.focus != null) return;
        Vec2 c = center(s, new Vec2());
        if(enemyHp(c.x, c.y, contactRadius) > 0f) return;
        int total = 0, ready = 0;
        for(Member m : s.members){
            if(m.state != State.hold) continue;
            total++;
            Unit u = Groups.unit.getByID(m.id);
            if(u != null && u.within(m.slotX, m.slotY, tilesize * 8f)) ready++;
        }
        if(total == 0 || ready < total * 0.6f) return;

        float dx = s.advanceX - c.x, dy = s.advanceY - c.y, dst = Mathf.len(dx, dy);
        s.lastStep = Time.time;
        if(dst <= advanceStep){
            translate(s, dx, dy);
            s.tactic = Tactic.hold;
            ui.showInfoFade(Core.bundle.get("client.squad.arrived"));
        }else{
            translate(s, dx / dst * advanceStep, dy / dst * advanceStep);
        }
    }

    /** Осторожный режим: при перевесе врага линия пятится к ядру, когда опасность ушла - возвращается. */
    private static void cautious(Squad s){
        if(Time.time - s.lastStep < fallCooldown || s.retreatAll || s.focus != null) return;
        Vec2 c = center(s, new Vec2());
        float enemy = enemyHp(c.x, c.y, enemyRadius), factor = Core.settings.getInt("squad-outnumber", 150) / 100f;
        Building core = player.team().core();
        float fallen = Mathf.len(s.fallX, s.fallY);

        if(enemy > s.hp * factor && core != null && !core.within(c.x, c.y, coreKeepOut)){
            float dx = core.x - c.x, dy = core.y - c.y, dst = Mathf.len(dx, dy), step = Math.min(fallStep, dst);
            dx = dx / dst * step;
            dy = dy / dst * step;
            s.fallX += dx;
            s.fallY += dy;
            s.lastStep = Time.time;
            translate(s, dx, dy);
        }else if(fallen > 1f && enemy < s.hp * 0.7f){
            float step = Math.min(fallStep, fallen);
            float dx = -s.fallX / fallen * step, dy = -s.fallY / fallen * step;
            s.fallX += dx;
            s.fallY += dy;
            s.lastStep = Time.time;
            translate(s, dx, dy);
        }
    }

    private static float enemyHp(float x, float y, float radius){
        float sum = 0f;
        for(Unit e : Groups.unit){
            if(e.isValid() && !e.dead && hostile(e.team) && !e.inFogTo(player.team()) && e.within(x, y, radius)) sum += e.health + e.shield;
        }
        return sum;
    }

    /** Новые боевые юниты с движением по умолчанию встают в ближайший отряд, где уже есть такой тип. */
    private static void reinforce(){
        boolean enabled = Core.settings.getBool("squad-reinforce", true);
        for(Unit u : player.team().data().units){
            if(known.contains(u.id)) continue;
            known.add(u.id);
            if(!enabled || !usable(u) || !u.type.canAttack) continue;
            if(u.lastCommanded != null && !u.lastCommanded.isEmpty()) continue;
            UnitCommand command = ((CommandAI)u.controller()).command;
            if(command != null && command != UnitCommand.moveCommand) continue;

            Squad best = null;
            float bestDst = Float.MAX_VALUE;
            Vec2 c = new Vec2();
            for(Squad s : squads){
                if(s.members.isEmpty() || !s.types.contains(u.type)) continue;
                center(s, c);
                float d = u.dst2(c);
                if(d < bestDst){
                    bestDst = d;
                    best = s;
                }
            }
            if(best != null) join(best, u);
        }
    }

    /** Новичок сразу идёт к ближайшему занятому месту; ровная расстановка - при следующем пересчёте. */
    private static void join(Squad s, Unit u){
        Member m = new Member(u.id);
        Member near = s.members.min(o -> u.dst2(o.slotX, o.slotY));
        m.slotX = near.slotX;
        m.slotY = near.slotY;
        s.members.add(m);
        order(m.id, m.slotX, m.slotY, null);
    }

    // ---------- фокус огня ----------

    private static void setFocus(Squad s, Teamc target, boolean onlyInRange, boolean auto){
        endFocus(s);
        for(Member m : s.members){
            if(m.state != State.hold) continue;
            Unit u = Groups.unit.getByID(m.id);
            if(u == null || !canHit(u, target)) continue;
            if(onlyInRange && !u.within(target, u.range() + (target instanceof Hitboxc z ? z.hitSize() / 2f : target instanceof Building b ? b.hitSize() / 2f : 0f))) continue;
            s.focusIds.add(m.id);
            if(!m.yielding) order(m.id, target.getX(), target.getY(), target);
        }
        if(s.focusIds.isEmpty()){
            if(!auto) ui.showInfoFade(Core.bundle.get("client.squad.nofocus"));
            return;
        }
        s.focus = target;
        s.autoFocus = auto;
    }

    // ---------- автоосада ----------

    /** Порядок целей: турели, затем ремонтники, затем ядро, затем прочие постройки на пути. Меньше - важнее. */
    private static int siegePriority(Building b){
        if(b.block.flags.contains(BlockFlag.turret)) return 0;
        if(b.block.flags.contains(BlockFlag.repair) || b.block instanceof MendProjector || b.block instanceof RegenProjector) return 1;
        if(b.block.flags.contains(BlockFlag.core)) return 2;
        return 3;
    }

    /** Отряд сам выбирает вражескую постройку у линии и бьёт её фокусом; ручной фокус игрока не трогаем. */
    private static void siege(Squad s){
        if(!Core.settings.getBool("squad-siege", true)){
            if(s.autoFocus && s.focus != null) endFocus(s);
            return;
        }
        if(s.retreatAll || (s.focus != null && !s.autoFocus)) return;

        Building best = siegeTarget(s);
        if(best == null){
            if(s.focus != null) endFocus(s);
            return;
        }
        if(best == s.focus) return;
        //нынешнюю цель того же приоритета добиваем, чтобы отряд не метался между соседними постройками
        if(s.focus instanceof Building now && siegePriority(now) <= siegePriority(best) && lineDst(s, now.x, now.y) <= siegeRange(now)) return;
        setFocus(s, best, false, true);
    }

    private static float siegeRange(Building b){
        return siegePriority(b) < 3 ? siegeRadius : obstacleRadius;
    }

    private static @Nullable Building siegeTarget(Squad s){
        FloatSeq line = s.line;
        if(line.size < 4) return null;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for(int i = 0; i < line.size; i += 2){
            minX = Math.min(minX, line.items[i]);
            maxX = Math.max(maxX, line.items[i]);
            minY = Math.min(minY, line.items[i + 1]);
            maxY = Math.max(maxY, line.items[i + 1]);
        }

        //постройки, до которых кто-то из отряда уже не смог дойти, не берём - иначе отряд упрётся в них навсегда
        IntSet unreachable = new IntSet();
        boolean anyGround = false;
        for(Member m : s.members){
            Unit u = Groups.unit.getByID(m.id);
            if(u == null || !u.type.canAttack || !u.type.targetGround) continue;
            anyGround = true;
            if(u.controller() instanceof CommandAI ai) unreachable.addAll(ai.unreachableBuildings);
        }
        if(!anyGround) return null;

        Seq<Building> found = new Seq<>();
        for(Teams.TeamData data : state.teams.present){
            if(!hostile(data.team) || data.buildingTree == null) continue;
            data.buildingTree.intersect(minX - siegeRadius, minY - siegeRadius, maxX - minX + siegeRadius * 2f, maxY - minY + siegeRadius * 2f, found);
        }

        Building best = null;
        int bestPriority = Integer.MAX_VALUE;
        float bestDst = Float.MAX_VALUE;
        for(Building b : found){
            if(!b.isValid() || b.inFogTo(player.team()) || unreachable.contains(b.pos())) continue;
            int priority = siegePriority(b);
            if(priority > bestPriority) continue;
            if(priority == 3 && !Core.settings.getBool("squad-attackblockers", true)) continue;
            // Пустые турели атакует только заданный процент юнитов (детерминированно по id).
            if(priority == 0 && b instanceof Turret.TurretBuild tb && !tb.hasAmmo()){
                int emptyPercent = Core.settings.getInt("squad-empty-turret-percent", 20);
                if(emptyPercent <= 0 || (b.id % 100) >= emptyPercent) continue;
            }
            float dst = lineDst(s, b.x, b.y);
            if(dst > siegeRange(b)) continue;
            if(priority < bestPriority || dst < bestDst){
                best = b;
                bestPriority = priority;
                bestDst = dst;
            }
        }
        return best;
    }

    /** Расстояние от точки до ломаной линии отряда. */
    private static float lineDst(Squad s, float x, float y){
        FloatSeq line = s.line;
        float best = Float.MAX_VALUE;
        for(int i = 2; i < line.size; i += 2){
            Vec2 p = Intersector.nearestSegmentPoint(line.items[i - 2], line.items[i - 1], line.items[i], line.items[i + 1], x, y, Tmp.v4);
            best = Math.min(best, p.dst(x, y));
        }
        return best;
    }

    /** Focus fire for every formed squad (focus marks). Quiet: no "nobody can hit" popup. */
    public static void focusAll(Teamc target){
        if(target == null) return;
        for(Unit unit : control.input.selectedUnits){
            if(usable(unit) && canHit(unit, target) && !anySquadContains(unit.id)){
                order(unit.id, target.getX(), target.getY(), target);
            }
        }
        for(Squad s : squads){
            if(s.members.isEmpty() || s.retreatAll) continue;
            boolean any = false;
            for(Member m : s.members){
                Unit u = Groups.unit.getByID(m.id);
                if(u != null && m.state == State.hold && canHit(u, target)){ any = true; break; }
            }
            if(any) setFocus(s, target, false, false);
        }
    }

    private static boolean anySquadContains(int id){
        for(Squad s : squads){
            if(s.members.contains(member -> member.id == id)) return true;
        }
        return false;
    }

    public static void endFocusAll(){
        for(Squad s : squads) if(s.focus != null) endFocus(s);
    }

    private static boolean canHit(Unit u, Teamc target){
        if(!u.type.canAttack || !hostile(target.team())) return false;
        return target instanceof Unit e ? u.canTarget(e) : u.type.targetGround;
    }

    private static void endFocus(Squad s){
        s.focus = null;
        s.autoFocus = false;
        for(VolleyGroup g : s.volleys){
            g.timer = -1f;
            g.done = false;
        }
        for(Member m : s.members){
            if(s.focusIds.contains(m.id) && m.state == State.hold && !s.retreatAll && !m.yielding && !UnitClearLogic.isClearing(m.id)) order(m.id, m.slotX, m.slotY, null);
        }
        s.focusIds.clear();
    }

    // ---------- отступление и возврат ----------

    private static void retreat(Squad s, Member m, Unit u){
        Building heal = healPoint(u);
        if(heal == null) return;
        if(m.healPos != -1) healLoad.increment(m.healPos, -1);
        m.state = State.retreat;
        m.healPos = heal.pos();
        m.yielding = false;
        m.yieldFor = -1;
        m.yieldX = m.yieldY = m.yieldFromX = m.yieldFromY = 0f;
        healLoad.increment(m.healPos);
        s.focusIds.remove(m.id);
        Vec2 spot = healSpot(u, heal, new Vec2());
        order(m.id, spot.x, spot.y, null);
    }

    /** Ремонтная точка, к которой шёл юнит, разрушена или сменила команду - пора искать другую. */
    private static boolean healGone(Member m){
        if(m.healPos == -1) return false;
        Building b = world.build(m.healPos);
        return b == null || !b.isValid() || b.team != player.team();
    }

    /**
     * Куда вести раненого: сам ремонтный блок твёрдый, точка в его центре для наземного юнита недостижима,
     * поэтому берём ближайшую к юниту проходимую клетку вокруг здания. Крупным юнитам кольцо отодвигается на их размер.
     */
    private static Vec2 healSpot(Unit u, Building heal, Vec2 out){
        out.set(heal.x, heal.y);
        if(u.type.flying || u.isFlying()) return out;

        int size = heal.block.size;
        int minX = heal.tileX() - (size - 1) / 2, maxX = heal.tileX() + size / 2;
        int minY = heal.tileY() - (size - 1) / 2, maxY = heal.tileY() + size / 2;
        int first = Math.max(1, Mathf.ceil(u.hitSize / tilesize / 2f));

        for(int ring = first; ring <= first + 5; ring++){
            float bestDst = Float.MAX_VALUE;
            int bx = 0, by = 0;
            for(int tx = minX - ring; tx <= maxX + ring; tx++){
                for(int ty = minY - ring; ty <= maxY + ring; ty++){
                    //только сам периметр кольца
                    if(tx != minX - ring && tx != maxX + ring && ty != minY - ring && ty != maxY + ring) continue;
                    if(!walkable(u, tx, ty)) continue;
                    float d = Mathf.dst2(u.x, u.y, tx * tilesize, ty * tilesize);
                    if(d < bestDst){
                        bestDst = d;
                        bx = tx;
                        by = ty;
                    }
                }
            }
            if(bestDst != Float.MAX_VALUE) return out.set(bx * tilesize, by * tilesize);
        }
        //вокруг всё занято - пусть идёт к центру, хоть подойдёт ближе
        return out;
    }

    private static boolean walkable(Unit u, int tx, int ty){
        var tile = world.tile(tx, ty);
        return tile != null && !tile.solid() && u.canPass(tx, ty);
    }

    private static void comeBack(Squad s, Member m, Unit u){
        m.state = State.hold;
        m.yielding = false;
        m.yieldFor = -1;
        m.yieldX = m.yieldY = m.yieldFromX = m.yieldFromY = 0f;
        if(m.healPos != -1) healLoad.increment(m.healPos, -1);
        m.healPos = -1;
        if(s.focus != null && canHit(u, s.focus)){
            s.focusIds.add(m.id);
            order(m.id, s.focus.getX(), s.focus.getY(), s.focus);
        }else{
            order(m.id, m.slotX, m.slotY, null);
        }
    }

    private static void toggleRetreatAll(Squad s){
        rebuildHealLoad();
        s.retreatAll = !s.retreatAll;
        float low = s.retreatPercent() / 100f;
        for(Member m : s.members){
            Unit u = Groups.unit.getByID(m.id);
            if(!usable(u)) continue;
            if(s.retreatAll){
                if(m.state == State.hold) retreat(s, m, u);
            }else if(m.state == State.retreat && u.health / u.maxHealth >= low){
                comeBack(s, m, u);
            }
        }
    }

    private static void rebuildHealLoad(){
        healLoad.clear();
        for(Squad s : squads){
            for(Member m : s.members){
                if(m.state == State.retreat && m.healPos != -1) healLoad.increment(m.healPos);
            }
        }
    }

    /** Ближайшая с учётом очереди ремонтная точка, к которой можно дойти не через чужой огонь; иначе безопасное ядро. */
    private static Building healPoint(Unit u){
        OreSafety.ensureFresh();
        Building best = null;
        float bestScore = Float.MAX_VALUE;
        for(Building b : indexer.getFlagged(player.team(), BlockFlag.repair)){
            if(!b.isValid()) continue;
            if(healCrowded(u, b)) continue;
            float score = b.dst(u) + healLoad.get(b.pos(), 0) * healLoadPenalty;
            //дорогая проверка пути только у тех, кто и так лучше текущего
            if(score < bestScore && !OreSafety.isThreatened(b.x, b.y) && OreSafety.pathSafe(u.x, u.y, b.x, b.y)){
                bestScore = score;
                best = b;
            }
        }
        if(best != null) return best;
        Building core = OreSafety.nearestSafeCore(u);
        return core != null ? core : u.closestCore();
    }

    /** Сколько раненых уже стоит у ремонтной точки; переполненные пропускаем, если юнит ещё не там. */
    private static boolean healCrowded(Unit u, Building b){
        int limit = Core.settings.getInt("squad-heal-crowd", 3);
        if(limit <= 0) return false;
        float radius = b.block.size * tilesize * 0.5f + tilesize * 6f;
        if(u.dst2(b) <= radius * radius * 0.81f) return false;
        int count = 0;
        for(Unit other : Groups.unit){
            if(other == null || other == u || other.dead || !other.isValid()) continue;
            if(other.team != player.team() || !other.damaged()) continue;
            if(other.dst2(b) <= radius * radius) count++;
        }
        return count >= limit;
    }

    // ---------- синхронизация залпа ----------

    private static boolean ignoreEnemyUnits(){
        return Core.settings.getBool("squad-ignore-enemy-units", true);
    }

    private static int volleyDelay(){
        return Mathf.clamp(Core.settings.getInt("squad-volley-delay", defaultVolleyDelay), 0, 300);
    }

    /** Радиус, в котором цели разных юнитов считаются одной группой залпа. */
    private static float volleyTargetRadius(){
        return Mathf.clamp(Core.settings.getInt("squad-volley-target-radius", defaultVolleyTargetRadius), 0, 100) * tilesize;
    }

    /** Залп синхронизируем для «Ворона» и «Омуры»: у них долгая перезарядка и тяжёлый выстрел. */
    private static boolean isVolleyUnit(Unit u){
        return u.type == UnitTypes.corvus || u.type == UnitTypes.omura;
    }

    private static boolean hasEnemyUnitsNear(Unit u){
        float checkRange = u.range() * 2f;
        float r2 = checkRange * checkRange;
        for(Unit e : Groups.unit){
            if(e == null || e.dead || !e.isValid() || e.team == u.team) continue;
            if(e.dst2(u) <= r2) return true;
        }
        return false;
    }

    private static void releaseVolley(Unit u, Member m){
        if(!m.holdFireForVolley){
            m.waitingForVolley = false;
            return;
        }
        if(u != null && u.controller() instanceof CommandAI ai) ai.setStance(UnitStance.holdFire, false);
        m.waitingForVolley = false;
        m.holdFireForVolley = false;
    }

    private static void releaseVolley(Squad s, VolleyGroup g){
        for(Member m : s.members){
            if(m.volleyGroup != g || !m.holdFireForVolley) continue;
            releaseVolley(Groups.unit.getByID(m.id), m);
        }
    }

    private static void holdVolley(Unit u, Member m){
        if(m.waitingForVolley || !(u.controller() instanceof CommandAI ai)) return;
        ai.setStance(UnitStance.holdFire, true);
        m.waitingForVolley = true;
        m.holdFireForVolley = true;
    }

    /** Держит огонь у членов одной группы залпа: общая или близкая цель, один тип. */
    private static void holdVolley(Squad s, VolleyGroup g){
        for(Member m : s.members){
            if(m.volleyGroup != g || m.state != State.hold) continue;
            Unit u = Groups.unit.getByID(m.id);
            if(u == null || !usable(u) || u.isPlayer()) continue;
            holdVolley(u, m);
        }
    }

    private static void syncVolleys(){
        for(Squad s : squads){
            if(s.members.any()) syncVolley(s);
        }
    }

    /**
     * Залп синхронизируется по типу и по общей/близкой цели.
     * В одну группу попадают только «Вороны» (или только «Омуры»), выбравшие одну цель
     * или цели в радиусе {@code squad-volley-target-radius} (по умолчанию 10 блоков).
     * Пока хотя бы один юнит группы не вышел на свой радиус атаки, вся группа держит огонь.
     */
    private static void syncVolley(Squad s){
        boolean ignore = ignoreEnemyUnits();
        float targetRadius = volleyTargetRadius();

        for(VolleyGroup g : s.volleys){
            g.assigned = false;
            g.allInRange = true;
            g.defend = false;
        }

        for(Member m : s.members){
            m.volleyGroup = null;
            Unit u = Groups.unit.getByID(m.id);
            if(m.state != State.hold || u == null || !usable(u) || u.isPlayer() || !isVolleyUnit(u) || !(u.controller() instanceof CommandAI ai)){
                if(m.holdFireForVolley) releaseVolley(u, m);
                continue;
            }

            Teamc target = volleyTarget(s, m, u, ai);
            if(target == null){
                // Цели нет: не держим юнит, пусть сам ищет цель и стреляет.
                if(m.holdFireForVolley) releaseVolley(u, m);
                continue;
            }

            VolleyGroup g = volleyGroup(s, u.type, target, targetRadius);
            if(g == null){
                g = new VolleyGroup();
                g.type = u.type;
                g.target = target;
                s.volleys.add(g);
            }
            m.volleyGroup = g;
            g.assigned = true;
            if(!ignore && hasEnemyUnitsNear(u)) g.defend = true;
            if(!inVolleyRange(u, target)) g.allInRange = false;
        }

        int delay = volleyDelay();
        for(int i = s.volleys.size - 1; i >= 0; i--){
            VolleyGroup g = s.volleys.get(i);
            if(!g.assigned){
                s.volleys.remove(i);
                continue;
            }

            if(g.defend){
                releaseVolley(s, g);
                g.timer = -1f;
                g.done = true;
                continue;
            }

            // Залп этой группы по текущим целям уже выпущен: не мешаем стрелять, пока цели не пропадут.
            if(g.done){
                releaseVolley(s, g);
                g.timer = -1f;
                continue;
            }

            if(!g.allInRange){
                g.timer = -1f;
                holdVolley(s, g);
                continue;
            }

            if(delay <= 0){
                releaseVolley(s, g);
                g.timer = -1f;
                g.done = true;
                continue;
            }

            if(g.timer < 0f) g.timer = Time.time;
            if(Time.time - g.timer >= delay){
                releaseVolley(s, g);
                g.timer = -1f;
                g.done = true;
            }else{
                holdVolley(s, g);
            }
        }
    }

    /** Группа того же типа с такой же или близкой целью; null - нужна новая группа. */
    private static @Nullable VolleyGroup volleyGroup(Squad s, UnitType type, Teamc target, float radius){
        VolleyGroup near = null;
        for(VolleyGroup g : s.volleys){
            if(g.type != type) continue;
            if(g.target == target) return g;
            if(near == null && validTarget(g.target) && g.target.within(target, radius)) near = g;
        }
        return near;
    }

    /** Цель юнита: фокус отряда, цель ИИ или ближайший враг в радиусе обнаружения. */
    private static @Nullable Teamc volleyTarget(Squad s, Member m, Unit u, CommandAI ai){
        if(s.focus != null && s.focusIds.contains(m.id) && validTarget(s.focus)) return s.focus;
        Teamc target = ai.currentTarget();
        if(validTarget(target)) return target;
        return nearestEnemy(u, u.range() * 2f);
    }

    private static boolean inVolleyRange(Unit u, Teamc target){
        return u.within(target, u.range() * 0.95f + (target instanceof Hitboxc z ? z.hitSize() / 2f : 0f));
    }

    private static boolean validTarget(@Nullable Teamc t){
        return t != null && hostile(t.team()) && targetAlive(t);
    }

    private static @Nullable Unit nearestEnemy(Unit u, float range){
        Unit best = null;
        float bestDst = range * range;
        for(Unit e : Groups.unit){
            if(e == null || e.dead || !e.isValid() || e.team == u.team || !u.canTarget(e)) continue;
            float dst = e.dst2(u);
            if(dst <= bestDst){
                bestDst = dst;
                best = e;
            }
        }
        return best;
    }

    // ---------- право прохода ----------

    /** Направление движения раненого: скорость, а если стоит - направление к ремонтной точке. */
    private static boolean retreatDirection(Member ret, Unit ru, Vec2 out){
        if(ru.vel.len2() >= 0.01f){
            out.set(ru.vel).nor();
            return true;
        }
        if(ret.healPos != -1){
            Building heal = world.build(ret.healPos);
            if(heal != null && Mathf.dst2(ru.x, ru.y, heal.x, heal.y) > 0.01f){
                out.set(heal.x - ru.x, heal.y - ru.y).nor();
                return true;
            }
        }
        return false;
    }

    private static void yieldRightOfWay(){
        for(Squad s : squads){
            for(Member ret : s.members){
                if(ret.state != State.retreat) continue;
                Unit ru = Groups.unit.getByID(ret.id);
                if(!usable(ru) || ru.isPlayer() || !retreatDirection(ret, ru, Tmp.v1)) continue;
                float dx = Tmp.v1.x, dy = Tmp.v1.y;

                boolean taken = false;
                for(Squad t : squads){
                    for(Member m : t.members){
                        if(m.yielding && m.yieldFor == ret.id){
                            taken = true;
                            break;
                        }
                    }
                    if(taken) break;
                }
                if(taken) continue;

                Member best = null;
                Unit bestUnit = null;
                float bestDst = Float.MAX_VALUE;
                for(Squad t : squads){
                    for(Member m : t.members){
                        if(m == ret || m.state == State.retreat || m.yielding) continue;
                        Unit u = Groups.unit.getByID(m.id);
                        if(!usable(u) || u.isPlayer()) continue;
                        float relX = u.x - ru.x, relY = u.y - ru.y;
                        float along = relX * dx + relY * dy;
                        if(along < 0f || along > tilesize * 4f) continue;
                        float side = relX * -dy + relY * dx;
                        float clear = (u.hitSize + ru.hitSize) / 2f + 6f;
                        if(Math.abs(side) > clear) continue;
                        float dst = u.dst2(ru);
                        if(dst < bestDst){
                            bestDst = dst;
                            best = m;
                            bestUnit = u;
                        }
                    }
                }
                if(best == null) continue;

                float relX = bestUnit.x - ru.x, relY = bestUnit.y - ru.y;
                float sideSign = (relX * -dy + relY * dx) >= 0f ? 1f : -1f;
                float dirX = -dy * sideSign, dirY = dx * sideSign;
                float clear = (bestUnit.hitSize + ru.hitSize) / 2f + 6f;
                if(!sideSpot(bestUnit, dirX, dirY, clear)) continue;

                best.yielding = true;
                best.yieldFor = ret.id;
                best.yieldX = sideSpot.x;
                best.yieldY = sideSpot.y;
                best.yieldFromX = bestUnit.x;
                best.yieldFromY = bestUnit.y;
                order(best.id, best.yieldX, best.yieldY, null);
            }
        }
    }

    private static boolean yieldStillNeeded(Member m, Unit u){
        if(!m.yielding || m.yieldFor == -1) return false;
        for(Squad s : squads){
            Member ret = s.members.find(r -> r.id == m.yieldFor);
            if(ret == null) continue;
            if(ret.state != State.retreat) return false;
            Unit ru = Groups.unit.getByID(m.yieldFor);
            if(!usable(ru) || ru.isPlayer() || !retreatDirection(ret, ru, Tmp.v1)) return false;

            // Раненый прошёл точку, где стоял уступающий, - можно возвращаться на место.
            float relX = m.yieldFromX - ru.x, relY = m.yieldFromY - ru.y;
            float along = relX * Tmp.v1.x + relY * Tmp.v1.y;
            if(along < -tilesize * 1.5f) return false;

            float maxDist = tilesize * 8f;
            return u.dst2(ru) <= maxDist * maxDist;
        }
        return false;
    }

    private static boolean sideSpot(Unit u, float dirX, float dirY, float distance){
        for(int i = 0; i < 5; i++){
            float dist = distance + i * tilesize;
            int tx = World.toTile(u.x + dirX * dist), ty = World.toTile(u.y + dirY * dist);
            if(!walkable(u, tx, ty)) continue;
            Tile tile = world.tile(tx, ty);
            sideSpot.set(tile.worldx(), tile.worldy());
            return true;
        }
        return false;
    }

    // ---------- очередь приказов ----------

    private static void unqueue(int id){
        for(int i = queue.size - 1; i >= 0; i--){
            Order o = queue.get(i);
            if(o.ids.removeValue(id) && o.ids.isEmpty()) queue.remove(i);
        }
    }

    private static void order(int id, float x, float y, @Nullable Teamc target){
        //новый приказ юниту заменяет ещё не отправленный старый
        unqueue(id);
        if(target == null){
            x = Mathf.clamp(x, 0f, world.unitWidth());
            y = Mathf.clamp(y, 0f, world.unitHeight());
        }
        //юниты с одной целью или одной точкой назначения уходят одним пакетом
        for(Order o : queue){
            if(target != null ? o.target == target : o.target == null && o.x == x && o.y == y){
                o.ids.add(id);
                return;
            }
        }
        Order o = new Order(x, y, target);
        o.ids.add(id);
        queue.add(o);
    }

    private static void flush(){
        if(queue.isEmpty() || Time.millis() < nextSend) return;
        while(queue.any()){
            Order o = queue.first();
            IntSeq batch = new IntSeq();
            while(o.ids.size > 0 && batch.size < maxIdsPerPacket){
                int id = o.ids.pop();
                if(usable(Groups.unit.getByID(id))) batch.add(id);
            }
            if(o.ids.isEmpty()) queue.remove(0);
            if(batch.isEmpty()) continue;
            if(o.target != null && !targetAlive(o.target)) continue;
            IntSeq move = new IntSeq();
            for(int i = 0; i < batch.size; i++){
                Unit unit = Groups.unit.getByID(batch.get(i));
                if(unit.controller() instanceof CommandAI ai && ai.currentCommand() != UnitCommand.moveCommand
                    && unit.type.allowCommand(unit, UnitCommand.moveCommand)) move.add(unit.id);
            }
            if(!move.isEmpty()) Call.setUnitCommand(player, move.toArray(), UnitCommand.moveCommand);
            Call.commandUnits(player, batch.toArray(), o.target instanceof Building b ? b : null, o.target instanceof Unit u ? u : null, new Vec2(o.x, o.y), false, true);
            nextSend = Time.millis() + sendIntervalMs;
            return;
        }
    }

    // ---------- отрисовка ----------

    private static void draw(){
        float px = Core.camera.width / Math.max(Core.graphics.getWidth(), 1);
        Draw.z(Layer.overlayUI + 0.3f);

        if(overlay != null && tool == Tool.draw && draft.size >= 4){
            drawPath(draft, 0f, 0f, 0f, Pal.accent, 0.8f, 3f * px);
        }
        for(Squad s : squads){
            if(s.members.any()) drawSquad(s, px);
        }
        Draw.reset();
    }

    private static void drawPath(FloatSeq path, float dx, float dy, float angle, Color color, float alpha, float stroke){
        Lines.stroke(stroke, Tmp.c1.set(color).a(alpha));
        float cx = (path.items[0] + path.items[path.size - 2]) / 2f, cy = (path.items[1] + path.items[path.size - 1]) / 2f;
        Lines.beginLine();
        for(int i = 0; i < path.size; i += 2){
            Tmp.v1.set(path.items[i] - cx, path.items[i + 1] - cy).rotate(angle).add(cx + dx, cy + dy);
            Lines.linePoint(Tmp.v1.x, Tmp.v1.y);
        }
        Lines.endLine();
    }

    private static void drawSquad(Squad s, float px){
        boolean selected = s == cur();
        Color main = selected ? Pal.accent : Color.lightGray;
        if(s.line.size >= 4){
            drawPath(s.line, 0f, 0f, 0f, main, s.shape == Shape.line ? 0.8f : 0.3f, 3f * px);
            if(selected && overlay != null && tool == Tool.move && dragging){
                drawPath(s.line, previewX, previewY, previewAngle, Color.white, 0.8f, 2f * px);
            }
        }

        for(Member m : s.members){
            Unit u = Groups.unit.getByID(m.id);
            if(u == null) continue;
            if(m.state == State.retreat){
                Lines.stroke(1.5f * px, Tmp.c1.set(Pal.heal).a(0.7f));
                Lines.line(u.x, u.y, m.slotX, m.slotY);
                Draw.color(Pal.heal, 0.8f);
            }else{
                Draw.color(main, 0.8f);
            }
            Lines.stroke(1.5f * px);
            Lines.square(m.slotX, m.slotY, 4f * px, 45f);
        }

        if(s.focus != null){
            Lines.stroke(2f * px, Pal.remove);
            Lines.circle(s.focus.getX(), s.focus.getY(), 12f + Mathf.absin(Time.time, 6f, 3f));
        }
        if(s.tactic == Tactic.advance){
            Lines.stroke(2f * px, Tmp.c1.set(Pal.accent).a(0.8f));
            Lines.poly(s.advanceX, s.advanceY, 3, 9f);
        }
        Draw.color();

        if(s.line.size >= 2){
            Drawf.text(Core.bundle.format("client.squad.label", s.index + 1, s.members.size, s.healing), s.line.items[0], s.line.items[1] + 16f * px, Color.white, px * 3f);
        }
    }

    // ---------- панель состояния ----------

    private static void buildHud(){
        hud = new Table();
        hud.touchable = Touchable.childrenOnly;
        hud.visible(() -> state.isGame() && anySquad() && ui.hudfrag.shown);

        Table p = new Table(Styles.black6);
        p.margin(6f);
        p.touchable = Touchable.enabled;
        p.defaults().pad(2f);

        p.table(row -> {
            row.defaults().pad(1f).size(72f, 34f);
            for(int i = 0; i < squadCount; i++){
                int index = i;
                TextButton b = row.button("", Styles.flatTogglet, () -> select(index)).get();
                b.update(() -> {
                    b.setChecked(current == index);
                    b.setText((index + 1) + (squads[index].members.any() ? " [lightgray](" + squads[index].members.size + ")" : ""));
                });
            }
        }).row();

        p.add(new Bar(() -> Core.bundle.format("client.squad.hp", Mathf.round(fraction() * 100f)), () -> Pal.heal, SquadCommander::fraction)).growX().height(20f).row();
        p.label(() -> Core.bundle.format("client.squad.status", cur().healing, cur().lost)).left().row();

        TextButton shape = p.button("", Styles.flatt, () -> {
            Squad s = cur();
            if(s.members.isEmpty()) return;
            s.shape = Shape.values()[(s.shape.ordinal() + 1) % Shape.values().length];
            layout(s, true);
        }).growX().height(34f).get();
        shape.update(() -> shape.setText(Core.bundle.get("client.squad.shape." + cur().shape.name())));
        p.row();

        p.table(row -> {
            row.defaults().pad(1f).size(72f, 34f);
            tactic(row, Tactic.hold);
            tactic(row, Tactic.advance);
            tactic(row, Tactic.cautious);
        }).row();

        p.table(row -> {
            row.defaults().pad(1f).size(36f);
            action(row, Icon.pencilSmall, "client.squad.btn.redraw", () -> openTool(Tool.draw), () -> overlay != null && tool == Tool.draw);
            action(row, Icon.moveSmall, "client.squad.btn.move", () -> {
                if(overlay != null && tool == Tool.move) closeOverlay();
                else if(cur().members.any()) openTool(Tool.move);
            }, () -> overlay != null && tool == Tool.move);
            action(row, Icon.commandAttackSmall, "client.squad.btn.focus", () -> {
                if(cur().focus != null) endFocus(cur());
                else if(cur().members.any()) openTool(Tool.focus);
            }, () -> cur().focus != null || overlay != null && tool == Tool.focus);
            action(row, Icon.undoSmall, "client.squad.btn.retreat", () -> {
                if(cur().members.any()) toggleRetreatAll(cur());
            }, () -> cur().retreatAll);
            action(row, Icon.settingsSmall, "client.squad.settings", SquadCommander::showSettings);
            action(row, Icon.trashSmall, "client.squad.btn.disband", SquadCommander::disband);
        }).row();

        p.addCaptureListener(new InputListener(){
            float lastX, lastY;
            boolean dragging;

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(button != KeyCode.mouseRight || !hudMoveMode()) return false;
                lastX = event.stageX;
                lastY = event.stageY;
                dragging = true;
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer){
                if(!dragging) return;
                hud.moveBy(event.stageX - lastX, event.stageY - lastY);
                lastX = event.stageX;
                lastY = event.stageY;
                clampHud();
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(!dragging) return;
                dragging = false;
                saveHudPosition();
            }
        });

        hud.add(p);
        hud.pack();
        restoreHudPosition();
        hud.update(SquadCommander::clampHud);
        Core.scene.add(hud);
    }

    private static boolean hudMoveMode(){
        return Core.settings.getBool("squad-hud-move", true);
    }

    private static void toggleHudMove(){
        boolean enabled = !hudMoveMode();
        Core.settings.put("squad-hud-move", enabled);
        ui.showInfoFade(Core.bundle.get("client.squad.hud." + (enabled ? "movable" : "locked")));
    }

    private static void restoreHudPosition(){
        if(Core.settings.has("squad-hud-x") && Core.settings.has("squad-hud-y")){
            hud.setPosition(Core.settings.getFloat("squad-hud-x"), Core.settings.getFloat("squad-hud-y"));
        }else{
            hud.setPosition(Core.graphics.getWidth() - hud.getWidth() - 4f, (Core.graphics.getHeight() - hud.getHeight()) / 2f);
        }
        clampHud();
    }

    private static void saveHudPosition(){
        Core.settings.put("squad-hud-x", hud.x);
        Core.settings.put("squad-hud-y", hud.y);
    }

    private static void clampHud(){
        if(hud == null) return;
        hud.x = Mathf.clamp(hud.x, 0f, Math.max(0f, Core.graphics.getWidth() - hud.getWidth()));
        hud.y = Mathf.clamp(hud.y, 0f, Math.max(0f, Core.graphics.getHeight() - hud.getHeight()));
    }

    private static float fraction(){
        return cur().maxHp <= 0f ? 0f : Mathf.clamp(cur().hp / cur().maxHp);
    }

    private static void tactic(Table row, Tactic t){
        TextButton b = row.button(Core.bundle.get("client.squad.tactic." + t.name()), Styles.flatTogglet, () -> {
            Squad s = cur();
            if(s.members.isEmpty()) return;
            if(t == Tactic.advance){
                openTool(Tool.advance);
            }else{
                s.tactic = t;
                s.fallX = s.fallY = 0f;
                s.lastStep = -999f;
            }
        }).get();
        b.update(() -> b.setChecked(overlay != null && tool == Tool.advance ? t == Tactic.advance : cur().tactic == t));
    }

    private static void action(Table row, arc.scene.style.Drawable icon, String tip, Runnable run){
        row.button(icon, Styles.clearNonei, run).tooltip(Core.bundle.get(tip));
    }

    private static void action(Table row, arc.scene.style.Drawable icon, String tip, Runnable run, Boolp checked){
        row.button(icon, Styles.clearTogglei, run).checked(b -> checked.get()).tooltip(Core.bundle.get(tip));
    }

    public static void showSettings(){
        if(settingsDialog == null){
            settingsDialog = new BaseDialog(Core.bundle.get("client.squad.settings"));
            settingsDialog.addCloseButton();
        }
        Squad s = cur();
        settingsDialog.cont.clear();
        settingsDialog.cont.table(t -> {
            t.defaults().left().pad(4);
            t.add(Core.bundle.format("client.squad.set.title", s.index + 1)).row();
            slider(t, "squad" + s.index + "-retreat", s.retreatPercent(), 10, 90, 5, v -> Core.bundle.format("client.squad.set.retreat", v));
            slider(t, "squad" + s.index + "-return", s.returnPercent(), 30, 100, 5, v -> Core.bundle.format("client.squad.set.return", v));
            slider(t, "squad-outnumber", 150, 100, 300, 10, v -> Core.bundle.format("client.squad.set.outnumber", v));
            check(t, "squad-ranks", "client.squad.set.ranks");
            check(t, "squad-reinforce", "client.squad.set.reinforce");
            check(t, "squad-closegaps", "client.squad.set.closegaps");
            check(t, "squad-siege", "client.squad.set.attackbuildings");
            check(t, "squad-blocksonly", "client.squad.set.blocksonly");
            check(t, "squad-attackblockers", "client.squad.set.attackblockers");
            check(t, "squad-ignore-enemy-units", "client.squad.set.ignoreenemyunits");
            slider(t, "squad-volley-delay", defaultVolleyDelay, 0, 120, 5, v -> Core.bundle.format("client.squad.set.volleydelay", v));
            slider(t, "squad-volley-target-radius", defaultVolleyTargetRadius, 0, 50, 1, v -> Core.bundle.format("client.squad.set.volleyradius", v));
            slider(t, "squad-empty-turret-percent", 20, 0, 100, 5, v -> Core.bundle.format("client.squad.set.emptyturret", v));
            slider(t, "squad-heal-crowd", 3, 0, 10, 1, v -> Core.bundle.format("client.squad.set.healcrowd", v));
            t.add(Core.bundle.get("client.squad.key")).row();
        });
        settingsDialog.show();
    }

    private static void check(Table t, String key, String name){
        t.check(Core.bundle.get(name), Core.settings.getBool(key, true), v -> Core.settings.put(key, v)).row();
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

    /** Прозрачный слой на весь экран для выбранного инструмента: линия, сдвиг, цель фокуса, точка наступления. */
    private static class Overlay extends Table{
        Overlay(){
            setFillParent(true);
            touchable = Touchable.enabled;
            top();
            Table panel = new Table(Styles.black6);
            panel.margin(8f);
            panel.touchable = Touchable.enabled;
            panel.label(() -> {
                String hint = Core.bundle.get("client.squad.hint" + (tool == Tool.draw ? "" : "." + tool.name()));
                if(tool != Tool.draw) return hint;
                int sel = 0;
                for(Unit u : control.input.selectedUnits) if(usable(u)) sel++;
                if(sel == 0) for(int i = 0; i < picked.size; i++) if(usable(Groups.unit.getByID(picked.get(i)))) sel++;
                return hint + "\n[lightgray]" + (sel > 0 ? Core.bundle.format("client.squad.selected", sel) : cur().members.any() ? Core.bundle.format("client.squad.keep", cur().members.size) : Core.bundle.get("client.squad.onscreen"));
            }).padRight(8);
            panel.button(Icon.settingsSmall, Styles.clearNonei, SquadCommander::showSettings).size(36f);
            panel.button(Icon.cancelSmall, Styles.clearNonei, SquadCommander::closeOverlay).size(36f);
            add(panel).padTop(40f);

            addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(event.targetActor != Overlay.this) return false;
                    if(tool == Tool.move){
                        if(button != KeyCode.mouseLeft && button != KeyCode.mouseRight) return false;
                        Vec2 m = Core.input.mouseWorld();
                        dragStartX = m.x;
                        dragStartY = m.y;
                        previewX = previewY = previewAngle = 0f;
                        rotating = button == KeyCode.mouseRight;
                        dragging = true;
                        return true;
                    }
                    if(button == KeyCode.mouseRight){
                        Core.app.post(SquadCommander::closeOverlay);
                    }else if(button == KeyCode.mouseLeft){
                        if(tool == Tool.draw){
                            draft.clear();
                            drawing = true;
                        }else if(tool == Tool.focus){
                            Core.app.post(SquadCommander::pickFocus);
                        }else if(tool == Tool.advance){
                            Core.app.post(SquadCommander::pickAdvance);
                        }
                    }
                    return true;
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(tool == Tool.move){
                        if(dragging) Core.app.post(SquadCommander::commitMove);
                    }else if(button == KeyCode.mouseLeft && drawing){
                        Core.app.post(SquadCommander::finishLine);
                    }
                }
            });
        }
    }
}
