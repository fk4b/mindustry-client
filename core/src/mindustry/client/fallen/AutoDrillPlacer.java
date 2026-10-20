package mindustry.client.fallen;

import arc.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.production.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Авторасстановка буров на жилу.
 * Клавиша (по умолчанию H) -> клик по жиле -> выбор бура -> клик по точке, куда вывести руду.
 *
 * Раскладка: ряды конвейеров через каждые (2 * размер бура + 1) тайлов, буры по обе стороны ряда.
 * Ряды сходятся в один конвейер-коллектор сбоку поля, от его конца строится маршрут до точки вывода.
 * Если включено ограничение, самые бедные буры отбрасываются, пока суммарный выход не влезет в один конвейер.
 */
public class AutoDrillPlacer{
    private enum Step{vein, drill, target}

    private static final int maxVein = 3000, routeMargin = 60, maxRouteNodes = 300_000, maxRouteDist = 300;
    /** Пересчёт раскладки, когда курсор задержался на тайле, а не на каждом движении мыши. */
    private static final long recomputeDelay = 60;
    private static final int stepCost = 10, turnCost = 4;
    private static final Point2[] dirs = Geometry.d4;

    private static boolean hooked;
    private static Step step; //null - режим выключен
    private static Overlay overlay;
    private static BaseDialog settingsDialog;

    //жила под курсором (шаг vein) или выбранная
    private static Item item;
    private static final IntSeq vein = new IntSeq();
    private static final IntSet veinSet = new IntSet();
    //рабочая область: ближайшие к клику тайлы жилы, которых хватает на один конвейер
    private static int fx0, fy0, fx1, fy1, focusX, focusY, focusCount;

    private static final Seq<Drill> drills = new Seq<>();
    private static final Seq<Conveyor> conveyors = new Seq<>();
    private static final ObjectMap<Drill, Layout> previews = new ObjectMap<>();
    private static Drill drill;
    private static Conveyor conveyor;
    /** Мост для маршрута; null - мосты недоступны. */
    private static BufferedItemBridge bridge;
    private static Layout layout;
    private static int lastTarget = -1;
    private static long targetChanged;
    private static boolean targetDirty;

    //буферы A*, переиспользуются между расчётами
    private static int[] routeCost = {}, routeParent = {};
    private static byte[] routePass = {};

    //кэш оценки позиции бура в пределах одного расчёта: центр -> кол-во руды или -1
    private static final IntIntMap drillCache = new IntIntMap();
    private static final ObjectIntMap<Item> oreCounts = new ObjectIntMap<>();
    private static final Seq<Tile> tmpTiles = new Seq<>();
    //тайлы, занятые ещё не построенными планами игрока - например, только что поставленным соседним полем
    private static final IntSet plannedTiles = new IntSet();

    private static class Layout{
        final Seq<BuildPlan> plans = new Seq<>();
        final IntSet occupied = new IntSet();
        int drills, conveyors, route = -1; //route: -1 без маршрута, -2 путь не найден
        float rate, rawRate;
        boolean chess; //поле на мостах
        BuildPlan end; //последний тайл коллектора, от него идёт маршрут
        int flowRot;
        final IntSeq bridgeLinks = new IntSeq(); //пары (мост, следующий мост или -1)
    }

    private static class DrillPos{
        final int x, y, bu, ore;

        DrillPos(int x, int y, int bu, int ore){
            this.x = x;
            this.y = y;
            this.bu = bu;
            this.ore = ore;
        }
    }

    private static class Row{
        final int vb;
        final Seq<DrillPos> drills = new Seq<>();

        Row(int vb){
            this.vb = vb;
        }
    }

    public static void init(){
        if(hooked) return;
        hooked = true;
        Events.run(Trigger.update, () -> {
            if(!state.isGame()){
                if(step != null) stop();
                return;
            }
            if(Core.input.keyTap(Binding.auto_drill) && Core.scene.getKeyboardFocus() == null && !control.input.commandMode) toggle();
            if(step != null) update();
        });

        Events.run(Trigger.draw, () -> {
            if(step != null && state.isGame()) draw();
        });

        Events.on(WorldLoadEvent.class, e -> stop());
    }

    public static boolean active(){
        return step != null;
    }

    public static void toggle(){
        if(step != null) stop();
        else start();
    }

    private static void start(){
        if(player.unit() == null || !player.unit().canBuild()) ui.showInfoFade(Core.bundle.get("client.drill.cantbuild"));
        step = Step.vein;
        resetVein();
        if(overlay != null) overlay.remove();
        overlay = new Overlay();
        Core.scene.add(overlay);
        rebuildPanel();
    }

    public static void stop(){
        step = null;
        drill = null;
        layout = null;
        previews.clear();
        resetVein();
        if(overlay != null){
            Overlay o = overlay;
            overlay = null;
            //вызов может прийти из touchDown оверлея - удалять сразу нельзя
            Core.app.post(o::remove);
        }
    }

    private static void back(){
        if(step == Step.target){
            step = Step.drill;
            drill = null;
            layout = null;
        }else if(step == Step.drill){
            step = Step.vein;
            resetVein();
        }else{
            stop();
            return;
        }
        rebuildPanel();
    }

    private static void update(){
        Tile tile = world.tileWorld(Core.input.mouseWorldX(), Core.input.mouseWorldY());
        if(step == Step.vein){
            hoverVein(tile);
        }else if(step == Step.target){
            int pos = tile == null ? -1 : tile.pos();
            if(pos != lastTarget){
                lastTarget = pos;
                targetChanged = Time.millis();
                targetDirty = true;
            }
            if(targetDirty && Time.timeSinceMillis(targetChanged) >= recomputeDelay) recomputeTarget(tile);
        }
    }

    private static void click(){
        Tile tile = world.tileWorld(Core.input.mouseWorldX(), Core.input.mouseWorldY());
        if(step == Step.vein || step == Step.drill){
            //на шаге выбора бура клик по другой руде перевыбирает жилу
            if(step == Step.drill && (tile == null || tile.drop() == null)) return;
            //заливаем заново от точки клика: от неё зависит, какую часть большой жилы занимать
            resetVein();
            hoverVein(tile);
            selectVein();
        }else if(step == Step.target){
            if(targetDirty) recomputeTarget(tile);
            place();
        }
    }

    private static void recomputeTarget(Tile tile){
        targetDirty = false;
        layout = tile == null ? null : compute(drill, tile);
    }

    // ---------- жила ----------

    private static void resetVein(){
        item = null;
        vein.clear();
        veinSet.clear();
    }

    private static void hoverVein(Tile tile){
        //тайлы под зданиями (в т.ч. брошенными) не жила: там бур не встанет, а заливка уходила бы под ними на соседнюю руду или песок
        if(tile == null || tile.drop() == null || tile.build != null || tile.block().isStatic()){
            resetVein();
            return;
        }
        if(tile.drop() == item && veinSet.contains(tile.pos())) return;

        resetVein();
        item = tile.drop();

        //заливка 4-связной области с той же рудой; это обход в ширину от тайла под курсором,
        //поэтому тайлы в vein идут по удалению от точки клика
        vein.add(tile.pos());
        veinSet.add(tile.pos());
        for(int i = 0; i < vein.size && vein.size < maxVein; i++){
            int x = Point2.x(vein.get(i)), y = Point2.y(vein.get(i));
            for(Point2 d : dirs){
                Tile o = world.tile(x + d.x, y + d.y);
                if(o != null && o.drop() == item && o.build == null && !o.block().isStatic() && veinSet.add(o.pos())) vein.add(o.pos());
            }
        }
    }

    /** Руда внутри поля идёт по мостам, а не конвейерам. */
    private static boolean bridgeTransport(){
        return bridge != null && Core.settings.getBool("autodrill-transport-bridge", false);
    }

    private static float transportSpeed(){
        return bridgeTransport() ? bridge.displayedSpeed : conveyor.displayedSpeed;
    }

    private static float capRate(){
        return Core.settings.getBool("autodrill-cap", true) ? transportSpeed() * Core.settings.getInt("autodrill-cappct", 100) / 100f : Float.MAX_VALUE;
    }

    /** На огромной жиле берём только ближайшие к клику тайлы - с запасом на то, что буры покроют не всю руду. */
    private static void focus(Drill d){
        int n = vein.size;
        float cap = capRate();
        if(cap < Float.MAX_VALUE) n = Math.min(n, Mathf.ceil(cap / drillRate(d, 1) * 1.7f) + d.size * d.size * 2);
        focusCount = n;
        fx0 = fy0 = Integer.MAX_VALUE;
        fx1 = fy1 = Integer.MIN_VALUE;
        for(int i = 0; i < n; i++){
            int x = Point2.x(vein.get(i)), y = Point2.y(vein.get(i));
            fx0 = Math.min(fx0, x);
            fy0 = Math.min(fy0, y);
            fx1 = Math.max(fx1, x);
            fy1 = Math.max(fy1, y);
        }
        focusX = Point2.x(vein.first());
        focusY = Point2.y(vein.first());
    }

    private static void selectVein(){
        if(item == null) return;

        drills.clear();
        for(Block b : content.blocks()){
            //буры с питанием (лазерный, воздушный) пропускаем: им ещё нужны энергия и вода, это автоматикой не подвести
            if(b instanceof Drill d && d.consPower == null && d.tier >= item.hardness && (d.blockedItems == null || !d.blockedItems.contains(item))
            && d.isPlaceable() && d.unlockedNow() && d.environmentBuildable()){
                drills.add(d);
            }
        }
        conveyors.clear();
        for(Block b : content.blocks()){
            //бронированный конвейер не принимает предметы от буров сбоку
            if(b instanceof Conveyor c && !(b instanceof ArmoredConveyor) && c.isPlaceable() && c.unlockedNow() && c.environmentBuildable()){
                conveyors.add(c);
            }
        }
        bridge = (BufferedItemBridge)content.blocks().find(b -> b instanceof BufferedItemBridge && b.isPlaceable() && b.unlockedNow() && b.environmentBuildable());
        if(drills.isEmpty() || conveyors.isEmpty()){
            ui.showInfoFade(drills.isEmpty() ? Core.bundle.format("client.drill.nodrill", item.localizedName) : Core.bundle.get("client.drill.noconveyor"));
            return;
        }
        String saved = Core.settings.getString("autodrill-conveyor", "");
        conveyor = conveyors.find(c -> c.name.equals(saved));
        if(conveyor == null) conveyor = conveyors.first();

        if(vein.size >= maxVein) ui.showInfoFade(Core.bundle.format("client.drill.veincap", maxVein));
        drill = null;
        layout = null;
        step = Step.drill;
        updatePreviews();

        //сразу берём бур минимального тира, который тут что-то поставит; другой можно выбрать кнопками на панели
        Drill def = null;
        for(Drill d : drills){
            if(previews.containsKey(d) && (def == null || d.tier < def.tier)) def = d;
        }
        if(def != null) chooseDrill(def);
        else rebuildPanel();
    }

    private static void updatePreviews(){
        previews.clear();
        for(Drill d : drills){
            Layout l = compute(d, null);
            if(l != null) previews.put(d, l);
        }
    }

    private static void chooseDrill(Drill d){
        drill = d;
        step = Step.target;
        lastTarget = -1;
        targetDirty = false;
        layout = null;
        rebuildPanel();
    }

    private static void chooseConveyor(Conveyor c){
        conveyor = c;
        Core.settings.put("autodrill-conveyor", c.name);
        Core.settings.put("autodrill-transport-bridge", false);
        recompute();
    }

    private static void chooseBridgeTransport(){
        Core.settings.put("autodrill-transport-bridge", true);
        recompute();
    }

    private static boolean useBridges(){
        return bridge != null && Core.settings.getBool("autodrill-bridge", false);
    }

    private static void chooseBridges(boolean b){
        Core.settings.put("autodrill-bridge", b);
        recompute();
    }

    private static boolean chess(){
        return Core.settings.getInt("autodrill-pattern", 0) == 1;
    }

    private static void choosePattern(boolean chess){
        Core.settings.put("autodrill-pattern", chess ? 1 : 0);
        recompute();
    }

    /** Пересчитать после смены настроек. */
    private static void recompute(){
        if(step == null || step == Step.vein) return;
        updatePreviews();
        lastTarget = -1;
        targetDirty = false;
        layout = null;
    }

    // ---------- раскладка ----------

    private static int minOre(Drill d){
        int area = d.size * d.size;
        return Math.max(1, Math.min(area, Mathf.ceil(area * Core.settings.getInt("autodrill-minore", 50) / 100f)));
    }

    /**
     * Приоритет бура при наборе под пропускную способность конвейера: чем больше, тем раньше берём.
     * Компактность 0% - сначала самые богатые места по всей области, 100% - сплошное пятно от точки клика.
     */
    private static float drillPriority(Drill d, int ore, int x, int y){
        float compact = Core.settings.getInt("autodrill-compact", 80) / 100f;
        float fill = ore / (float)(d.size * d.size);
        int far = Math.abs(x - focusX) + Math.abs(y - focusY);
        return fill * (1f - compact) * 20f - far * compact - far * 0.001f;
    }

    private static float drillRate(Drill d, int ore){
        return 60f / d.getDrillTime(item) * ore;
    }

    /** @param target точка вывода, null - без маршрута (превью для кнопок). */
    private static Layout compute(Drill d, Tile target){
        drillCache.clear();
        collectPlanned();
        focus(d);
        Layout best = chess() ? buildChess(d, target) : null;
        int s = d.size, period = 2 * s + 1;

        for(int o = 0; o < 2 && !chess(); o++){
            int uMin = o == 0 ? fx0 : fy0, uMax = o == 0 ? fx1 : fy1;
            int vMin = o == 0 ? fy0 : fx0, vMax = o == 0 ? fy1 : fx1;
            //коллектор ставим со стороны цели и ведём в её сторону
            int side = 1, flow = 1;
            if(target != null){
                int tu = o == 0 ? target.x : target.y, tv = o == 0 ? target.y : target.x;
                side = tu * 2 < uMin + uMax ? -1 : 1;
                flow = tv * 2 < vMin + vMax ? -1 : 1;
            }
            for(int ph = 0; ph < period; ph++){
                Layout l = build(d, o, ph, side, flow, uMin, uMax, vMin, vMax);
                if(l != null && (best == null || l.rate > best.rate + 0.001f || (l.rate > best.rate - 0.001f && l.plans.size < best.plans.size))){
                    best = l;
                }
            }
        }

        if(best != null && target != null && Core.settings.getBool("autodrill-route", true)) route(best, target);
        return best;
    }

    private static int x(int o, int u, int v){
        return o == 0 ? u : v;
    }

    private static int y(int o, int u, int v){
        return o == 0 ? v : u;
    }

    /** Поворот конвейера для шага (du, dv) в координатах раскладки. */
    private static int rot(int o, int du, int dv){
        int dx = o == 0 ? du : dv, dy = o == 0 ? dv : du;
        return dx > 0 ? 0 : dy > 0 ? 1 : dx < 0 ? 2 : 3;
    }

    private static boolean conveyorFits(int x, int y){
        return fits(conveyor, x, y);
    }

    private static Layout build(Drill d, int o, int ph, int side, int flow, int uMin, int uMax, int vMin, int vMax){
        int s = d.size, period = 2 * s + 1;

        Seq<Row> rows = new Seq<>();
        for(int vb = vMin - s + ph; vb - s <= vMax; vb += period){
            Row row = new Row(vb);
            strip(d, o, row, uMin, uMax, vb - s);
            strip(d, o, row, uMin, uMax, vb + 1);
            if(row.drills.any()) rows.add(row);
        }
        if(rows.isEmpty()) return null;

        int cu = side < 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        for(Row row : rows){
            for(DrillPos p : row.drills) cu = side < 0 ? Math.min(cu, p.bu - 1) : Math.max(cu, p.bu + s);
        }

        //на мостах ряд - цепочка через step тайлов: в любые s подряд тайлов попадает мост, значит его касается каждый бур
        boolean br = bridgeTransport();
        Block belt = br ? bridge : conveyor;
        int step = br ? Math.min(s, bridge.range) : 1;

        //ряд идёт от коллектора вглубь поля; упёрлись в препятствие - дальние буры отпадают
        for(Row row : rows){
            int need = farEdge(row, side, s);
            int last = cu;
            for(int u = cu - side * step; ; u -= side * step){
                if(!fits(belt, x(o, u, row.vb), y(o, u, row.vb))) break;
                last = u;
                if(side < 0 ? u >= need : u <= need) break;
            }
            int lastF = last;
            row.drills.removeAll(p -> side < 0 ? p.bu > lastF : p.bu + s - 1 < lastF);
        }

        //ограничение выхода одним конвейером: выкидываем самые бедные буры
        Seq<DrillPos> all = new Seq<>();
        for(Row row : rows) all.addAll(row.drills);
        Layout l = new Layout();
        for(DrillPos p : all) l.rawRate += drillRate(d, p.ore);
        l.rate = l.rawRate;
        float cap = capRate();
        if(cap < Float.MAX_VALUE){
            all.sort(p -> drillPriority(d, p.ore, p.x, p.y));
            while(all.any() && l.rate > cap){
                DrillPos p = all.remove(0);
                l.rate -= drillRate(d, p.ore);
                for(Row row : rows) row.drills.remove(p, true);
            }
        }
        rows.removeAll(r -> r.drills.isEmpty());
        if(rows.isEmpty()) return null;

        //коллектор: от дальнего ряда к ближнему к цели
        int vFrom = flow > 0 ? rows.min(r -> r.vb).vb : rows.max(r -> r.vb).vb;
        int vTo = flow > 0 ? rows.max(r -> r.vb).vb : rows.min(r -> r.vb).vb;
        int collectorRot = rot(o, 0, flow);
        if(br) return bridgeRows(d, l, rows, o, side, flow, cu, vFrom, vTo, step, collectorRot);
        for(int v = vFrom; ; v += flow){
            int cx = x(o, cu, v), cy = y(o, cu, v);
            if(!conveyorFits(cx, cy)) return null;
            BuildPlan plan = new BuildPlan(cx, cy, collectorRot, conveyor);
            l.plans.add(plan);
            l.occupied.add(Point2.pack(cx, cy));
            l.end = plan;
            if(v == vTo) break;
        }
        l.flowRot = collectorRot;

        int beltRot = rot(o, side, 0);
        for(Row row : rows){
            int need = farEdge(row, side, s);
            for(int u = cu - side; side < 0 ? u <= need : u >= need; u -= side){
                int bx = x(o, u, row.vb), by = y(o, u, row.vb);
                l.plans.add(new BuildPlan(bx, by, beltRot, conveyor));
                l.occupied.add(Point2.pack(bx, by));
            }
        }
        l.conveyors = l.plans.size;

        for(Row row : rows){
            for(DrillPos p : row.drills){
                l.plans.add(new BuildPlan(p.x, p.y, 0, d));
                Tile t = world.tile(p.x, p.y);
                for(Tile o2 : t.getLinkedTilesAs(d, tmpTiles)) l.occupied.add(o2.pos());
                l.drills++;
            }
        }
        return l;
    }

    /** Прямая схема на мостах: цепочки в рядах сходятся в цепочку-коллектор сбоку поля. */
    private static Layout bridgeRows(Drill d, Layout l, Seq<Row> rows, int o, int side, int flow, int cu, int vFrom, int vTo, int step, int collectorRot){
        //узлы коллектора: на уровне каждого ряда и промежуточные, если ряды дальше дальности моста
        IntSeq cv = new IntSeq();
        rows.sort(r -> r.vb * flow);
        int cur = vFrom;
        cv.add(cur);
        for(Row row : rows){
            while(cur != row.vb){
                cur = Math.abs(row.vb - cur) <= bridge.range ? row.vb : cur + flow * bridge.range;
                cv.add(cur);
            }
        }
        for(int i = 0; i < cv.size; i++){
            int cx = x(o, cu, cv.get(i)), cy = y(o, cu, cv.get(i));
            if(!fits(bridge, cx, cy)) return null;
        }
        for(int i = 0; i < cv.size; i++){
            int cx = x(o, cu, cv.get(i)), cy = y(o, cu, cv.get(i));
            BuildPlan plan;
            if(i + 1 < cv.size){
                int nx = x(o, cu, cv.get(i + 1)), ny = y(o, cu, cv.get(i + 1));
                plan = new BuildPlan(cx, cy, 0, bridge, new Point2(nx - cx, ny - cy));
                l.bridgeLinks.add(Point2.pack(cx, cy), Point2.pack(nx, ny));
            }else{
                plan = new BuildPlan(cx, cy, 0, bridge);
                l.bridgeLinks.add(Point2.pack(cx, cy), -1);
                l.end = plan;
            }
            l.plans.add(plan);
            l.occupied.add(Point2.pack(cx, cy));
        }
        l.flowRot = collectorRot;

        //ряд: каждый мост связан с соседним ближе к коллектору, ближний - с узлом коллектора на уровне ряда
        int s = d.size;
        for(Row row : rows){
            int need = farEdge(row, side, s);
            int prevX = x(o, cu, row.vb), prevY = y(o, cu, row.vb);
            for(int u = cu - side * step; ; u -= side * step){
                int bx = x(o, u, row.vb), by = y(o, u, row.vb);
                l.plans.add(new BuildPlan(bx, by, 0, bridge, new Point2(prevX - bx, prevY - by)));
                l.occupied.add(Point2.pack(bx, by));
                l.bridgeLinks.add(Point2.pack(bx, by), Point2.pack(prevX, prevY));
                prevX = bx;
                prevY = by;
                if(side < 0 ? u >= need : u <= need) break;
            }
        }
        l.conveyors = l.plans.size;
        l.chess = true;

        for(Row row : rows){
            for(DrillPos p : row.drills){
                l.plans.add(new BuildPlan(p.x, p.y, 0, d));
                for(Tile t : world.tile(p.x, p.y).getLinkedTilesAs(d, tmpTiles)) l.occupied.add(t.pos());
                l.drills++;
            }
        }
        return l;
    }

    /**
     * Шахматная раскладка для буров 2x2: мостовые конвейеры в узлах решётки с шагом 3, вокруг каждого моста
     * "вертушкой" четыре бура. Квадрат 6x6 закрывается целиком: 8 буров + 4 моста, каждый бур касается двух мостов.
     * Здесь - левые нижние углы буров внутри периода 6x6 относительно узла решётки.
     */
    private static final int[][] chessDrills = {{0, 1}, {2, 1}, {1, 3}, {1, 5}, {3, 4}, {5, 4}, {4, 0}, {4, 2}};

    private static Layout buildChess(Drill d, Tile target){
        if(d.size != 2 || bridge == null || bridge.range < 3) return null;
        //решётка привязана к координатам мира (без сдвига): соседние поля ложатся в одну мозаику и стыкуются бурами
        return buildChess(d, target, 0, 0);
    }

    private static boolean isChessDrill(int x, int y, int ox, int oy){
        int mx = Math.floorMod(x - ox, 6), my = Math.floorMod(y - oy, 6);
        for(int[] c : chessDrills){
            if(c[0] == mx && c[1] == my) return true;
        }
        return false;
    }

    //узлы решётки вокруг бура и направление от узла к буру - заполняет touching()
    private static final int[] touchNode = new int[8], touchDir = new int[8];

    /** @param ox, oy сдвиг решётки: мосты стоят там, где (x - ox) и (y - oy) делятся на 3. */
    private static Layout buildChess(Drill d, Tile target, int ox, int oy){
        //узлы решётки, покрывающие рабочую область с запасом в один шаг
        int i0 = Math.floorDiv(fx0 - 3 - ox, 3), i1 = Math.floorDiv(fx1 + 3 - ox, 3);
        int j0 = Math.floorDiv(fy0 - 3 - oy, 3), j1 = Math.floorDiv(fy1 + 3 - oy, 3);
        int lw = i1 - i0 + 1, lh = j1 - j0 + 1, nodes = lw * lh;
        byte[] fits = new byte[nodes]; //0 - не проверен, 1 - мост встанет, 2 - нет

        //кандидаты - позиции вертушки на жиле
        IntSeq cand = new IntSeq(), candOre = new IntSeq();
        for(int x = fx0 - 1; x <= fx1; x++){
            for(int y = fy0 - 1; y <= fy1; y++){
                if(!isChessDrill(x, y, ox, oy)) continue;
                int ore = evalDrill(d, x, y);
                if(ore <= 0) continue;
                cand.add(Point2.pack(x, y));
                candOre.add(ore);
            }
        }
        if(cand.isEmpty()) return null;

        //сначала самые богатые и ближние к клику, пока выход влезает в конвейер
        Integer[] order = new Integer[cand.size];
        float[] key = new float[cand.size];
        for(int c = 0; c < cand.size; c++){
            key[c] = -drillPriority(d, candOre.get(c), Point2.x(cand.get(c)), Point2.y(cand.get(c)));
            order[c] = c;
        }
        Arrays.sort(order, (a, b) -> Float.compare(key[a], key[b]));
        float cap = capRate(), picked = 0f;
        IntSeq chosen = new IntSeq();
        for(int c : order){
            float r = drillRate(d, candOre.get(c));
            if(picked + r > cap + 0.001f) continue;
            picked += r;
            chosen.add(c);
        }

        //выходной мост - вплотную к выбранным бурам, со стороны цели
        int tx = target == null ? fx0 - 1 : target.x, ty = target == null ? (fy0 + fy1) / 2 : target.y;
        int root = -1, rootDist = Integer.MAX_VALUE;
        for(int i = 0; i < chosen.size; i++){
            int c = chosen.get(i);
            int count = touching(Point2.x(cand.get(c)), Point2.y(cand.get(c)), ox, oy, lw, lh, i0, j0);
            for(int k = 0; k < count; k++){
                int n = touchNode[k];
                int dist = Math.abs(nodeX(n, lw, i0, ox) - tx) + Math.abs(nodeY(n, lw, j0, oy) - ty);
                if(dist < rootDist && nodeFits(fits, n, lw, i0, j0, ox, oy)){
                    root = n;
                    rootDist = dist;
                }
            }
        }
        if(root == -1) return null;
        int rx = nodeX(root, lw, i0, ox), ry = nodeY(root, lw, j0, oy);

        //дерево мостов растёт от выхода: буры по удалению от него, каждый цепляется к уже проложенной сети кратчайшим отростком
        int[] parent = new int[nodes], prev = new int[nodes];
        Arrays.fill(parent, -2); //-2 - не в сети, -1 - выход
        parent[root] = -1;
        Integer[] byDist = new Integer[chosen.size];
        for(int i = 0; i < chosen.size; i++) byDist[i] = chosen.get(i);
        Arrays.sort(byDist, (a, b) -> Integer.compare(
            Math.abs(Point2.x(cand.get(a)) - rx) + Math.abs(Point2.y(cand.get(a)) - ry),
            Math.abs(Point2.x(cand.get(b)) - rx) + Math.abs(Point2.y(cand.get(b)) - ry)));

        IntQueue queue = new IntQueue();
        IntSeq kept = new IntSeq();
        float rate = 0f;
        for(int c : byDist){
            int x = Point2.x(cand.get(c)), y = Point2.y(cand.get(c));
            int count = touching(x, y, ox, oy, lw, lh, i0, j0);

            boolean fed = false;
            for(int k = 0; k < count && !fed; k++){
                int n = touchNode[k];
                fed = parent[n] != -2 && (parent[n] == -1 || linkDir(n, parent[n], lw, i0, j0, ox, oy) != touchDir[k]);
            }

            if(!fed){
                //поиск в ширину от всей сети до узла рядом с буром, который примет его руду
                Arrays.fill(prev, -1);
                queue.clear();
                for(int n = 0; n < nodes; n++){
                    if(parent[n] != -2) queue.addLast(n);
                }
                int found = -1;
                while(!queue.isEmpty() && found == -1){
                    int u = queue.removeFirst(), ui = u % lw, uj = u / lw;
                    for(Point2 dir : dirs){
                        int ai = ui + dir.x, aj = uj + dir.y;
                        if(ai < 0 || aj < 0 || ai >= lw || aj >= lh) continue;
                        int v = ai + aj * lw;
                        if(parent[v] != -2 || prev[v] != -1 || !nodeFits(fits, v, lw, i0, j0, ox, oy)) continue;
                        prev[v] = u;
                        for(int k = 0; k < count; k++){
                            if(touchNode[k] == v && linkDir(v, u, lw, i0, j0, ox, oy) != touchDir[k]){
                                found = v;
                                break;
                            }
                        }
                        if(found != -1) break;
                        queue.addLast(v);
                    }
                }
                if(found == -1) continue; //не дотянуться - бур не ставим
                for(int n = found; parent[n] == -2; n = prev[n]) parent[n] = prev[n];
            }

            kept.add(c);
            rate += drillRate(d, candOre.get(c));
        }
        if(kept.isEmpty()) return null;

        Layout l = new Layout();
        l.end = new BuildPlan(rx, ry, 0, bridge);
        l.plans.add(l.end);
        l.occupied.add(Point2.pack(rx, ry));
        l.bridgeLinks.add(Point2.pack(rx, ry), -1);
        for(int n = 0; n < nodes; n++){
            if(parent[n] < 0) continue;
            int x = nodeX(n, lw, i0, ox), y = nodeY(n, lw, j0, oy);
            int px = nodeX(parent[n], lw, i0, ox), py = nodeY(parent[n], lw, j0, oy);
            l.plans.add(new BuildPlan(x, y, 0, bridge, new Point2(px - x, py - y)));
            l.occupied.add(Point2.pack(x, y));
            l.bridgeLinks.add(Point2.pack(x, y), Point2.pack(px, py));
        }
        l.conveyors = l.plans.size;
        for(int i = 0; i < kept.size; i++){
            int c = kept.get(i), x = Point2.x(cand.get(c)), y = Point2.y(cand.get(c));
            l.plans.add(new BuildPlan(x, y, 0, d));
            for(int dx = 0; dx < 2; dx++){
                for(int dy = 0; dy < 2; dy++) l.occupied.add(Point2.pack(x + dx, y + dy));
            }
        }
        l.drills = kept.size;
        l.rate = l.rawRate = rate;
        l.chess = true;
        //маршрут от выходного моста стартует наружу от центра поля
        int cx = (fx0 + fx1) / 2, cy = (fy0 + fy1) / 2;
        l.flowRot = Math.abs(rx - cx) >= Math.abs(ry - cy) ? (rx >= cx ? 0 : 2) : (ry >= cy ? 1 : 3);
        return l;
    }

    private static int nodeX(int n, int lw, int i0, int ox){
        return (n % lw + i0) * 3 + ox;
    }

    private static int nodeY(int n, int lw, int j0, int oy){
        return (n / lw + j0) * 3 + oy;
    }

    private static boolean nodeFits(byte[] fits, int n, int lw, int i0, int j0, int ox, int oy){
        if(fits[n] == 0) fits[n] = (byte)(fits(bridge, nodeX(n, lw, i0, ox), nodeY(n, lw, j0, oy)) ? 1 : 2);
        return fits[n] == 1;
    }

    /** Направление связи моста n на мост to. */
    private static int linkDir(int n, int to, int lw, int i0, int j0, int ox, int oy){
        int dx = Integer.signum(nodeX(to, lw, i0, ox) - nodeX(n, lw, i0, ox)), dy = Integer.signum(nodeY(to, lw, j0, oy) - nodeY(n, lw, j0, oy));
        return dx > 0 ? 0 : dy > 0 ? 1 : dx < 0 ? 2 : 3;
    }

    /**
     * Узлы решётки, касающиеся бура 2x2 с углом (x, y), и направление от узла к буру - в touchNode/touchDir.
     * Мост не берёт предметы со стороны, куда смотрит его связь, поэтому направление нужно для проверки.
     */
    private static int touching(int x, int y, int ox, int oy, int lw, int lh, int i0, int j0){
        int count = 0;
        for(int k = 0; k < 2; k++){
            for(int side = 0; side < 4; side++){
                int px = side == 0 ? x - 1 : side == 1 ? x + 2 : x + k;
                int py = side == 0 || side == 1 ? y + k : side == 2 ? y - 1 : y + 2;
                if(Math.floorMod(px - ox, 3) != 0 || Math.floorMod(py - oy, 3) != 0) continue;
                int ni = Math.floorDiv(px - ox, 3) - i0, nj = Math.floorDiv(py - oy, 3) - j0;
                if(ni < 0 || nj < 0 || ni >= lw || nj >= lh) continue;
                touchNode[count] = ni + nj * lw;
                touchDir[count] = side == 0 ? 0 : side == 1 ? 2 : side == 2 ? 1 : 3;
                count++;
            }
        }
        return count;
    }

    /** Ближний к коллектору край самого дальнего бура ряда - до него должен дойти конвейер. */
    private static int farEdge(Row row, int side, int s){
        int need = side < 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for(DrillPos p : row.drills) need = side < 0 ? Math.max(need, p.bu) : Math.min(need, p.bu + s - 1);
        return need;
    }

    /** Полоса буров высотой в бур, нижний край bv: подбираем сдвиг вдоль ряда с максимумом руды. */
    private static void strip(Drill d, int o, Row row, int uMin, int uMax, int bv){
        int s = d.size, off = (s - 1) / 2;
        Seq<DrillPos> best = null, cur = new Seq<>();
        float bestScore = 0f;
        for(int a = 0; a < s; a++){
            cur.clear();
            int sum = 0;
            for(int bu = uMin - s + 1 + a; bu <= uMax; bu += s){
                int cx = x(o, bu, bv) + off, cy = y(o, bu, bv) + off;
                int ore = evalDrill(d, cx, cy);
                if(ore > 0){
                    cur.add(new DrillPos(cx, cy, bu, ore));
                    sum += ore;
                }
            }
            //штраф за каждый бур: при той же руде выигрывает сдвиг, где буры встают на жилу целиком, а не половинками
            float score = sum - cur.size * 0.25f;
            if(sum > 0 && score > bestScore){
                bestScore = score;
                best = new Seq<>(cur);
            }
        }
        if(best != null) row.drills.addAll(best);
    }

    /** Сколько тайлов нужной руды возьмёт бур, или -1, если ставить нельзя или он будет копать другое. */
    private static int evalDrill(Drill d, int cx, int cy){
        int key = Point2.pack(cx, cy);
        int cached = drillCache.get(key, Integer.MIN_VALUE);
        if(cached != Integer.MIN_VALUE) return cached;

        int result = -1;
        Tile tile = world.tile(cx, cy);
        if(tile != null && Build.validPlaceIgnoreUnits(d, player.team(), cx, cy, 0, true, true) && !tile.getLinkedTilesAs(d, tmpTiles).contains(t -> plannedTiles.contains(t.pos()))){
            oreCounts.clear();
            for(Tile o : tile.getLinkedTilesAs(d, tmpTiles)){
                if(d.canMine(o)) oreCounts.increment(d.getDrop(o), 0, 1);
            }
            int mine = oreCounts.get(item, 0);
            if(mine >= minOre(d) && minesTarget(mine)) result = mine;
        }
        drillCache.put(key, result);
        return result;
    }

    /** Тот же выбор доминирующей руды, что в Drill.countOre. */
    private static boolean minesTarget(int mine){
        for(var e : oreCounts){
            Item other = e.key;
            if(other == item) continue;
            if(item.lowPriority != other.lowPriority){
                if(item.lowPriority) return false;
                continue;
            }
            if(e.value > mine || (e.value == mine && other.id > item.id)) return false;
        }
        return true;
    }

    // ---------- маршрут до точки вывода ----------

    private static void route(Layout l, Tile target){
        boolean bridges = useBridges();
        Block block = bridges ? bridge : conveyor;
        int hop = bridges ? bridge.range : 1;
        Building into = target.build != null && !l.occupied.contains(target.pos()) ? target.build : null;
        int sx = l.end.x, sy = l.end.y;
        if(Math.abs(sx - target.x) + Math.abs(sy - target.y) > maxRouteDist){
            l.route = -2;
            return;
        }

        int bx0 = Math.min(sx, target.x) - routeMargin, by0 = Math.min(sy, target.y) - routeMargin;
        int bx1 = Math.max(sx, target.x) + routeMargin, by1 = Math.max(sy, target.y) + routeMargin;
        bx0 = Math.max(bx0, 0);
        by0 = Math.max(by0, 0);
        bx1 = Math.min(bx1, world.width() - 1);
        by1 = Math.min(by1, world.height() - 1);
        int w = bx1 - bx0 + 1, h = by1 - by0 + 1;

        //цели: сама точка или свободные тайлы вокруг здания
        IntSet goals = new IntSet();
        if(into != null){
            for(Tile t : into.tile.getLinkedTiles(new Seq<>())){
                for(Point2 d : dirs){
                    Tile n = world.tile(t.x + d.x, t.y + d.y);
                    if(n != null && n.build != into && !l.occupied.contains(n.pos()) && fits(block, n.x, n.y)) goals.add(n.pos());
                }
            }
        }else if(!l.occupied.contains(target.pos()) && fits(block, target.x, target.y)){
            goals.add(target.pos());
        }
        if(goals.isEmpty()){
            l.route = -2;
            return;
        }

        //A* по состояниям (тайл, направление входа): штраф за повороты даёт прямые линии.
        //Мост может прыгнуть на 1..range тайлов поверх чего угодно, конвейер - только на соседний.
        int states = w * h * 4;
        if(routeCost.length < states){
            routeCost = new int[states];
            routeParent = new int[states];
            routePass = new byte[w * h];
        }
        int[] cost = routeCost, parent = routeParent;
        byte[] pass = routePass; //0 - не проверен, 1 - можно, 2 - нельзя
        Arrays.fill(cost, 0, states, Integer.MAX_VALUE);
        Arrays.fill(pass, 0, w * h, (byte)0);
        PriorityQueue<Long> open = new PriorityQueue<>();

        int startDir = l.flowRot;
        for(int dir = 0; dir < 4; dir++){
            int nx = sx + dirs[dir].x, ny = sy + dirs[dir].y;
            if(!passable(l, block, pass, bx0, by0, w, h, nx, ny)) continue;
            int st = ((nx - bx0) + (ny - by0) * w) * 4 + dir;
            int c = stepCost + (dir == startDir ? 0 : turnCost);
            cost[st] = c;
            parent[st] = -1;
            open.add(((long)(c + heuristic(nx, ny, target, hop)) << 32) | st);
        }

        int found = -1, expanded = 0;
        while(!open.isEmpty() && expanded++ < maxRouteNodes){
            long top = open.poll();
            int st = (int)top, f = (int)(top >>> 32);
            int idx = st / 4, dir = st % 4;
            int cx = idx % w + bx0, cy = idx / w + by0;
            if(f - heuristic(cx, cy, target, hop) > cost[st]) continue;
            if(goals.contains(Point2.pack(cx, cy))){
                found = st;
                break;
            }
            for(int nd = 0; nd < 4; nd++){
                //назад нельзя: у моста первый прыжок назад упёрся бы в коллектор, который его кормит
                if(nd == (dir + 2) % 4) continue;
                for(int len = 1; len <= hop; len++){
                    int nx = cx + dirs[nd].x * len, ny = cy + dirs[nd].y * len;
                    if(!passable(l, block, pass, bx0, by0, w, h, nx, ny)) continue;
                    int ns = ((nx - bx0) + (ny - by0) * w) * 4 + nd;
                    //длинный прыжок стоит чуть меньше - меньше мостов на тот же путь
                    int c = cost[st] + stepCost + (bridges ? hop - len : 0) + (nd == dir ? 0 : turnCost);
                    if(c < cost[ns]){
                        cost[ns] = c;
                        parent[ns] = st;
                        open.add(((long)(c + heuristic(nx, ny, target, hop)) << 32) | ns);
                    }
                }
            }
        }
        if(found == -1){
            l.route = -2;
            return;
        }

        IntSeq path = new IntSeq();
        for(int st = found; st != -1; st = parent[st]) path.add(st);
        path.reverse();

        l.end.rotation = path.first() % 4;
        int insert = l.conveyors;
        if(bridges){
            //каждый мост связан со следующим, последний сам выгружает в соседние здания
            for(int i = 0; i < path.size; i++){
                int idx = path.get(i) / 4, px = idx % w + bx0, py = idx / w + by0;
                int next = -1;
                Point2 config = null;
                if(i + 1 < path.size){
                    int nidx = path.get(i + 1) / 4, nx = nidx % w + bx0, ny = nidx / w + by0;
                    next = Point2.pack(nx, ny);
                    config = new Point2(nx - px, ny - py);
                }
                l.plans.insert(insert++, new BuildPlan(px, py, 0, bridge, config));
                l.bridgeLinks.add(Point2.pack(px, py), next);
            }
            l.route = path.size;
            return;
        }

        //каждый тайл смотрит на следующий, последний - в здание или по ходу движения
        for(int i = 0; i < path.size; i++){
            int idx = path.get(i) / 4;
            int px = idx % w + bx0, py = idx / w + by0;
            int r;
            if(i + 1 < path.size){
                r = path.get(i + 1) % 4;
            }else{
                r = path.get(i) % 4;
                if(into != null){
                    for(int dir = 0; dir < 4; dir++){
                        Tile n = world.tile(px + dirs[dir].x, py + dirs[dir].y);
                        if(n != null && n.build == into){
                            r = dir;
                            break;
                        }
                    }
                }
            }
            l.plans.insert(insert++, new BuildPlan(px, py, r, conveyor));
        }
        l.route = path.size;
    }

    private static int heuristic(int x, int y, Tile target, int hop){
        return (Math.abs(x - target.x) + Math.abs(y - target.y)) * stepCost / hop;
    }

    private static void collectPlanned(){
        plannedTiles.clear();
        if(player.unit() == null) return;
        for(BuildPlan plan : player.unit().plans){
            if(plan.breaking || plan.tile() == null) continue;
            for(Tile t : plan.tile().getLinkedTilesAs(plan.block, tmpTiles)) plannedTiles.add(t.pos());
        }
    }

    private static boolean fits(Block block, int x, int y){
        if(plannedTiles.contains(Point2.pack(x, y))) return false;
        return Build.validPlaceIgnoreUnits(block, player.team(), x, y, 0, true, true);
    }

    private static boolean passable(Layout l, Block block, byte[] pass, int bx0, int by0, int w, int h, int x, int y){
        int lx = x - bx0, ly = y - by0;
        if(lx < 0 || ly < 0 || lx >= w || ly >= h) return false;
        int i = lx + ly * w;
        if(pass[i] == 0) pass[i] = (byte)(!l.occupied.contains(Point2.pack(x, y)) && fits(block, x, y) ? 1 : 2);
        return pass[i] == 1;
    }

    // ---------- установка ----------

    private static void place(){
        if(layout == null){
            ui.showInfoFade(Core.bundle.get("client.drill.failed"));
            return;
        }
        if(player == null || player.unit() == null){
            ui.showInfoFade(Core.bundle.get("client.drill.nounit"));
            return;
        }
        if(layout.route == -2) ui.showInfoFade(Core.bundle.get("client.drill.noroute"));
        //Конфиг моста уже лежит в BuildPlan. Отдельной отправки на сервер нет.
        control.input.flushPlans(layout.plans);
        String route = layout.route > 0 ? Core.bundle.format("client.drill.routebit", layout.route) : "";
        ui.showInfoFade(Core.bundle.format("client.drill.placedline", layout.drills, layout.conveyors, route, Strings.autoFixed(layout.rate, 2)));

        //остаёмся в режиме, чтобы можно было сразу взять следующую жилу
        step = Step.vein;
        drill = null;
        layout = null;
        resetVein();
        rebuildPanel();
    }

    // ---------- отрисовка ----------

    private static void draw(){
        Draw.z(Layer.plans);

        //на шаге вывода ярче рабочая область вокруг клика, остальная жила - бледно
        int bright = step == Step.target ? focusCount : vein.size;
        for(int i = 0; i < vein.size; i++){
            if(step == Step.vein) Draw.color(Pal.accent, 0.35f);
            else Draw.color(Pal.accentBack, i < bright ? 0.3f : 0.1f);
            Fill.square(Point2.x(vein.get(i)) * tilesize, Point2.y(vein.get(i)) * tilesize, tilesize / 2f);
        }
        Draw.reset();

        Layout shown = step == Step.target ? layout : null;
        if(shown != null){
            for(BuildPlan plan : shown.plans) plan.block.drawPlan(plan, shown.plans, true, 0.85f);
        }

        if(step == Step.target){
            Tile t = world.tileWorld(Core.input.mouseWorldX(), Core.input.mouseWorldY());
            if(t != null){
                Draw.z(Layer.overlayUI);
                Lines.stroke(1.5f, shown != null && shown.route >= 0 ? Pal.accent : Pal.remove);
                if(t.build != null){
                    Lines.square(t.build.x, t.build.y, t.build.block.size * tilesize / 2f + 1f);
                }else{
                    Lines.square(t.worldx(), t.worldy(), tilesize / 2f + 1f);
                }
                Draw.reset();
            }
        }
    }

    // ---------- интерфейс ----------

    private static String hint(){
        if(step == Step.vein){
            return item == null ? Core.bundle.get("client.drill.hint.vein") : Core.bundle.format("client.drill.hint.veinpick", vein.size, item.localizedName);
        }
        if(step == Step.drill) return Core.bundle.get("client.drill.hint.drill");
        return Core.bundle.get("client.drill.hint.target");
    }

    private static String stats(Layout l){
        if(l == null) return step == Step.target ? Core.bundle.get("client.drill.stats.none") : "";
        String raw = l.rawRate > l.rate + 0.001f ? Core.bundle.format("client.drill.stats.raw", Strings.autoFixed(l.rawRate, 2)) : "";
        String transport = Core.bundle.format(l.chess ? "client.drill.stats.bridges" : "client.drill.stats.conveyors", l.conveyors);
        String route = "";
        if(l.route == -2) route = Core.bundle.get("client.drill.stats.noroute");
        else if(l.route >= 0) route = Core.bundle.format("client.drill.stats.route", l.route, Core.bundle.get(useBridges() ? "client.drill.stats.routebridge" : "client.drill.stats.routebelt"));
        return Core.bundle.format("client.drill.stats", l.drills, Strings.autoFixed(l.rate, 2), raw, Strings.autoFixed(transportSpeed(), 1), transport, route);
    }

    private static void rebuildPanel(){
        if(overlay == null) return;
        Table p = overlay.panel;
        p.clear();
        p.defaults().pad(2);

        p.table(head -> {
            head.label(() -> item == null ? "" : item.emoji()).padRight(4);
            head.add(Core.bundle.get("client.drill.title")).padRight(8);
            head.label(AutoDrillPlacer::hint).growX().left();
            head.button(Icon.settingsSmall, Styles.clearNonei, AutoDrillPlacer::showSettings).size(36f).tooltip(Core.bundle.get("client.drill.settings.tip"));
            head.button(Icon.cancelSmall, Styles.clearNonei, AutoDrillPlacer::stop).size(36f).tooltip(Core.bundle.get("client.drill.exit"));
        }).growX().row();

        if(step == Step.vein) return;

        p.table(t -> {
            t.left();
            for(Drill d : drills){
                Layout pre = previews.get(d);
                ImageButton b = t.button(new TextureRegionDrawable(d.uiIcon), Styles.clearNoneTogglei, 40f, () -> chooseDrill(d))
                .size(56f).checked(btn -> drill == d).tooltip(d.localizedName).get();
                b.setDisabled(() -> previews.get(d) == null);
                t.label(() -> {
                    Layout lp = previews.get(d);
                    return lp == null ? "[gray]-" : "x" + lp.drills + "\n[accent]" + Strings.autoFixed(lp.rate, 1) + Core.bundle.get("client.drill.per");
                }).padRight(10).left();
                if(pre == null) b.getImage().setColor(Pal.gray);
            }
            t.image().width(2f).fillY().color(Pal.gray).padLeft(4).padRight(8);
            for(Conveyor c : conveyors){
                t.button(new TextureRegionDrawable(c.uiIcon), Styles.clearNoneTogglei, 32f, () -> chooseConveyor(c))
                .size(46f).checked(btn -> !bridgeTransport() && conveyor == c).tooltip(Core.bundle.format("client.drill.speedtip", c.localizedName, Strings.autoFixed(c.displayedSpeed, 1)));
            }
            if(bridge != null){
                t.button(new TextureRegionDrawable(bridge.uiIcon), Styles.clearNoneTogglei, 32f, AutoDrillPlacer::chooseBridgeTransport)
                .size(46f).checked(btn -> bridgeTransport()).tooltip(Core.bundle.format("client.drill.inside", bridge.localizedName, Strings.autoFixed(bridge.displayedSpeed, 1)));
            }
            if(bridge != null){
                t.image().width(2f).fillY().color(Pal.gray).padLeft(4).padRight(8);
                t.add(Core.bundle.get("client.drill.outputlabel")).padRight(4);
                t.button(Icon.rightSmall, Styles.clearNoneTogglei, () -> chooseBridges(false))
                .size(46f).checked(btn -> !useBridges()).tooltip(Core.bundle.get("client.drill.bybelt"));
                t.button(new TextureRegionDrawable(bridge.uiIcon), Styles.clearNoneTogglei, 32f, () -> chooseBridges(true))
                .size(46f).checked(btn -> useBridges()).tooltip(Core.bundle.format("client.drill.bridgehop", bridge.localizedName));
            }
        }).left().row();

        p.table(t -> {
            t.left();
            t.add(Core.bundle.get("client.drill.pattern")).padRight(6);
            t.button(Core.bundle.get("client.drill.rows"), Styles.flatTogglet, () -> choosePattern(false)).size(120f, 36f).checked(b -> !chess())
            .tooltip(Core.bundle.get("client.drill.rows.tip"));
            t.button(Core.bundle.get("client.drill.chess"), Styles.flatTogglet, () -> choosePattern(true)).size(120f, 36f).checked(b -> chess())
            .disabled(b -> bridge == null).tooltip(Core.bundle.get("client.drill.chess.tip"));
        }).left().row();

        p.label(() -> step == Step.target ? stats(layout) : Core.bundle.get("client.drill.pick")).left();
    }

    public static void showSettings(){
        if(settingsDialog == null){
            settingsDialog = new BaseDialog(Core.bundle.get("client.drill.settings.title"));
            settingsDialog.addCloseButton();
            settingsDialog.cont.table(t -> {
                t.defaults().left().pad(4);
                slider(t, "autodrill-minore", 50, 0, 100, 5, v -> Core.bundle.format("client.drill.minore", v));
                slider(t, "autodrill-compact", 80, 0, 100, 10, v -> Core.bundle.format("client.drill.compact", v));
                t.check(Core.bundle.get("client.drill.cap"), Core.settings.getBool("autodrill-cap", true), b -> Core.settings.put("autodrill-cap", b)).row();
                slider(t, "autodrill-cappct", 100, 50, 300, 10, v -> Core.bundle.format("client.drill.cappct", v));
                t.check(Core.bundle.get("client.drill.route"), Core.settings.getBool("autodrill-route", true), b -> Core.settings.put("autodrill-route", b)).row();
                t.add(Core.bundle.get("client.drill.key")).row();
            });
            settingsDialog.hidden(AutoDrillPlacer::recompute);
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

    /** Прозрачный слой на весь экран: забирает клики по карте, сверху панель режима. */
    private static class Overlay extends Table{
        final Table panel = new Table(Styles.black6);

        Overlay(){
            setFillParent(true);
            touchable = Touchable.enabled;
            top();
            panel.margin(6f);
            //клики по фону панели не должны уходить в карту
            panel.touchable = Touchable.enabled;
            add(panel).padTop(40f).minWidth(420f);

            addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    if(event.targetActor != Overlay.this) return false;
                    //удалять элементы прямо в touchDown нельзя, поэтому через post
                    if(button == KeyCode.mouseLeft) Core.app.post(AutoDrillPlacer::click);
                    else if(button == KeyCode.mouseRight) Core.app.post(AutoDrillPlacer::back);
                    return true;
                }
            });
        }
    }
}
