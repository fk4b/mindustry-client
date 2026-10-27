package mindustry.client.fallen;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

/**
 * Прогноз следующей волны: выдержит ли оборона.
 *
 * Для каждого маршрута волны (наземные по полю потока, летающие - прямой линией к ядру) считаем,
 * сколько урона турели успевают нанести юниту, пока он идёт по маршруту: сумма DPS турелей, в чьём радиусе
 * лежит каждый тайл пути, умноженная на время на этом тайле (1 тайл / скорость юнита), с учётом брони.
 * Если D_t - урон, который вся оборона успеет нанести только юнитам типа t, а HP_t - их суммарное здоровье
 * со щитами, то волна "съедает" долю HP_t / D_t пропускной способности обороны. Сумма долей <= 1 - выдержит.
 * Модель грубая: не учитывает лечение, перегрев, промахи, сплэш по толпе и то, что юниты стреляют в ответ.
 *
 * На карте маршрут красится по плотности огня: красный - не простреливается, зелёный - плотный огонь.
 * Самый длинный непростреливаемый участок подписан.
 */
public class WaveForecast{
    public static boolean enabled = Core.settings.getBool("waveforecast", false);

    private static final float refreshTicks = 120f;
    private static final int maxSteps = 900;

    static class Route{
        final FloatSeq points = new FloatSeq();
        final FloatSeq dps = new FloatSeq(); //DPS по каждой точке, без учёта брони
        boolean air;
        int costType;
        float load; //доля обороны, которую съедает волна на этом маршруте
        //броня*10 -> урон за весь путь при скорости 1 тайл/с; одна и та же броня у многих типов
        final IntFloatMap potential = new IntFloatMap();
        //самый длинный непростреливаемый участок: индексы точек
        int gapFrom = -1, gapTo = -1;
    }

    static class TurretInfo{
        float x, y, range, dps, perShot;
        boolean air, ground;
    }

    private static boolean hooked;
    private static final Seq<Route> routes = new Seq<>();
    private static final Seq<TurretInfo> turrets = new Seq<>();
    private static float lastRefresh = -999f, maxDps = 1f;
    private static int warnedWave = -1;

    /** Итог: доля обороны, которую съест волна (<= 1 - выдержит). -1 - нечего считать. */
    public static float load = -1f;
    public static float waveHp, waveCount;
    private static String verdict = "";

    public static void init(){
        if(hooked) return;
        hooked = true;

        Events.run(Trigger.update, () -> {
            if(!state.isGame() || !state.rules.waves || player == null) return;
            boolean warn = Core.settings.getBool("waveforecast-warn", true);
            //без включённого прогноза считаем только перед волной, чтобы не грузить большие карты
            boolean soon = warn && state.rules.waveTimer && state.wavetime < 20f * 60f;
            if(!enabled && !soon) return;
            if(Time.time - lastRefresh >= refreshTicks){
                lastRefresh = Time.time;
                refresh();
            }
            //предупреждение за 15 с до волны, один раз на волну
            if(warn && load > 1f && state.rules.waveTimer && state.wavetime < 15f * 60f && warnedWave != state.wave){
                warnedWave = state.wave;
                NetClient.findCoords(ui.chatfrag.addMsg(Core.bundle.format("client.forecast.warn", state.wave, verdict) + weakText()));
                Sounds.uiNotify.play();
            }
        });

        Events.run(Trigger.draw, () -> {
            if(enabled && state.isGame()) draw();
        });

        Events.on(WorldLoadEvent.class, e -> {
            routes.clear();
            load = -1f;
            warnedWave = -1;
            lastRefresh = -999f;
        });
    }

    public static void toggle(){
        enabled = !enabled;
        Core.settings.put("waveforecast", enabled);
        lastRefresh = -999f;
        if(enabled && state.isGame()){
            refresh();
            new mindustry.client.ui.Toast(3).add(summary());
        }
    }

    public static void showSettings(){
        Core.settings.put("waveforecast-warn", !Core.settings.getBool("waveforecast-warn", true));
        new mindustry.client.ui.Toast(2).add(Core.bundle.get("client.forecast.warnset") + ": " + Core.bundle.get(Core.settings.getBool("waveforecast-warn", true) ? "mod.enabled" : "mod.disabled"));
    }

    /** Одна строка для тоста и подписи у ядра. */
    public static String summary(){
        if(load < 0f) return Core.bundle.get("client.forecast.none");
        return Core.bundle.format("client.forecast.head", state.wave, (int)waveCount, UI.formatAmount((long)waveHp)) + "\n" + verdict;
    }

    // ---------- расчёт ----------

    private static void refresh(){
        collectTurrets();
        buildRoutes();
        for(Route r : routes) measure(r);

        int w = state.wave - 1;
        int groundSpawns = Math.max(spawner.countGroundSpawns(), 1), flyerSpawns = Math.max(spawner.countFlyerSpawns(), 1);
        load = 0f;
        waveHp = 0f;
        waveCount = 0f;
        boolean any = false;

        for(SpawnGroup group : state.rules.spawns){
            int amt = group.getSpawned(w);
            if(amt <= 0) continue;
            UnitType type = group.type;
            boolean air = type.flying;
            int spawns = group.spawn != -1 ? 1 : air ? flyerSpawns : groundSpawns;
            int total = amt * spawns;
            float hp = type.health * (group.effect == null ? 1f : group.effect.healthMultiplier) + group.getShield(w);
            waveHp += hp * total;
            waveCount += total;

            //юниты делятся между маршрутами своего вида поровну; каждый маршрут проверяем отдельно, берём худший
            int cost = air ? -1 : costOf(type);
            int n = 0;
            for(Route r : routes) if(matches(r, air, cost)) n++;
            if(n == 0) continue;
            any = true;
            float speed = Math.max(type.speed * (group.effect == null ? 1f : group.effect.speedMultiplier), 0.05f);
            for(Route r : routes){
                if(!matches(r, air, cost)) continue;
                float dealt = damageAlong(r, type.armor, speed);
                float need = hp * total / n;
                r.load += dealt <= 0.01f ? 99f : need / dealt;
            }
        }
        if(!any){
            load = -1f;
            verdict = "";
            return;
        }
        for(Route r : routes) load = Math.max(load, r.load);

        if(load <= 1f){
            verdict = Core.bundle.format("client.forecast.holds", Strings.autoFixed(Math.min(1f / Math.max(load, 0.01f), 99f), 1));
        }else{
            int through = Mathf.clamp(Mathf.round((1f - 1f / load) * 100f), 1, 100);
            verdict = Core.bundle.format("client.forecast.breaks", through);
        }
    }

    private static boolean matches(Route r, boolean air, int cost){
        return r.air == air && (air || r.costType == cost);
    }

    private static int costOf(UnitType type){
        int c = type.flowfieldPathType;
        return c < 0 ? Pathfinder.costGround : c;
    }

    private static void collectTurrets(){
        turrets.clear();
        maxDps = 1f;
        for(Building b : player.team().data().buildings){
            if(!(b instanceof Turret.TurretBuild t) || !b.isValid()) continue;
            float dps = t.estimateDps();
            if(dps <= 0f) continue;
            TurretInfo info = new TurretInfo();
            info.x = b.x;
            info.y = b.y;
            info.range = t.range();
            info.dps = dps;
            info.perShot = t.peekAmmo() == null ? dps : Math.max(t.peekAmmo().estimateDPS(), 0.01f);
            info.air = t.targetAir();
            info.ground = t.targetGround();
            turrets.add(info);
        }
    }

    private static void buildRoutes(){
        routes.clear();
        Seq<UnitType> groundTypes = new Seq<>();
        boolean[] needAir = {false};
        for(SpawnGroup g : state.rules.spawns){
            if(g.type.flying) needAir[0] = true;
            else if(!groundTypes.contains(t -> costOf(t) == costOf(g.type))) groundTypes.add(g.type);
        }

        int[] walks = {0};
        spawner.eachGroundSpawn((Intc2)(x, y) -> {
            if(walks[0]++ >= 24) return;
            Tile start = world.tile(x, y);
            if(start == null) return;
            for(UnitType type : groundTypes){
                int cost = costOf(type);
                Route r = new Route();
                r.costType = cost;
                trace(r, start, pathfinder.getField(state.rules.waveTeam, cost, Pathfinder.fieldCore));
                if(r.points.size >= 4) routes.add(r);
            }
        });
        if(!needAir[0]) return;
        int[] flyers = {0};
        spawner.eachFlyerSpawn((Floatc2)(x, y) -> {
            if(flyers[0]++ >= 16) return;
            Building core = Geometry.findClosest(x, y, indexer.getEnemy(state.rules.waveTeam, BlockFlag.core));
            if(core == null) return;
            Route r = new Route();
            r.air = true;
            float len = Mathf.dst(x, y, core.x, core.y);
            int steps = Math.max((int)(len / tilesize), 1);
            for(int i = 0; i <= steps; i++){
                r.points.add(Mathf.lerp(x, core.x, i / (float)steps), Mathf.lerp(y, core.y, i / (float)steps));
            }
            routes.add(r);
        });
    }

    private static void trace(Route r, Tile tile, Pathfinder.Flowfield field){
        r.points.add(tile.worldx(), tile.worldy());
        for(int step = 0; step < maxSteps; step++){
            Tile next = pathfinder.getTargetTile(tile, field);
            if(next == null || next == tile) break;
            r.points.add(next.worldx(), next.worldy());
            tile = next;
        }
    }

    private static void measure(Route r){
        r.dps.clear();
        r.potential.clear();
        r.load = 0f;
        int n = r.points.size / 2;
        for(int i = 0; i < n; i++){
            float x = r.points.items[i * 2], y = r.points.items[i * 2 + 1];
            float sum = 0f;
            for(TurretInfo t : turrets){
                if((r.air ? t.air : t.ground) && Mathf.within(x, y, t.x, t.y, t.range)) sum += t.dps;
            }
            r.dps.add(sum);
            maxDps = Math.max(maxDps, sum);
        }

        //самый длинный участок без огня, не считая первых тайлов у точки появления
        r.gapFrom = r.gapTo = -1;
        int best = 0, start = -1, skip = Math.min(6, n / 4);
        for(int i = skip; i <= n; i++){
            boolean empty = i < n && r.dps.items[i] <= 0f;
            if(empty && start < 0) start = i;
            if(!empty && start >= 0){
                if(i - start > best){
                    best = i - start;
                    r.gapFrom = start;
                    r.gapTo = i - 1;
                }
                start = -1;
            }
        }
        if(best < 4) r.gapFrom = r.gapTo = -1;
    }

    /** Урон, который оборона нанесёт одному юниту за весь путь, если все турели бьют только его тип. */
    private static float damageAlong(Route r, float armor, float speed){
        int key = (int)(armor * 10f);
        float cached = r.potential.get(key, -1f);
        if(cached < 0f){
            cached = potential(r, armor);
            r.potential.put(key, cached);
        }
        return cached / (speed * 60f);
    }

    private static float potential(Route r, float armor){
        float total = 0f;
        int n = r.points.size / 2;
        for(int i = 0; i < n; i++){
            float step = i == 0 ? 0f : Mathf.dst(r.points.items[i * 2 - 2], r.points.items[i * 2 - 1], r.points.items[i * 2], r.points.items[i * 2 + 1]);
            if(r.dps.items[i] <= 0f || step <= 0f) continue;
            float seconds = step;
            float x = r.points.items[i * 2], y = r.points.items[i * 2 + 1];
            //броня режет каждый выстрел, поэтому считаем по турелям
            for(TurretInfo t : turrets){
                if(!(r.air ? t.air : t.ground) || !Mathf.within(x, y, t.x, t.y, t.range)) continue;
                float factor = Math.max(t.perShot - armor, t.perShot * minArmorDamage) / t.perShot;
                total += t.dps * factor * seconds;
            }
        }
        return total;
    }

    private static String weakText(){
        Route worst = null;
        for(Route r : routes){
            if(r.gapFrom >= 0 && (worst == null || r.load > worst.load)) worst = r;
        }
        if(worst == null) return "";
        int mid = (worst.gapFrom + worst.gapTo) / 2;
        return Core.bundle.format("client.forecast.weak", worst.gapTo - worst.gapFrom + 1,
            (int)(worst.points.items[mid * 2] / tilesize), (int)(worst.points.items[mid * 2 + 1] / tilesize));
    }

    // ---------- отрисовка ----------

    private static void draw(){
        if(load < 0f) return;
        float px = Core.camera.width / Math.max(Core.graphics.getWidth(), 1);
        Draw.z(Layer.overlayUI + 0.4f);

        for(Route r : routes){
            int n = r.points.size / 2;
            Lines.stroke((r.air ? 2f : 3f) * px);
            for(int i = 1; i < n; i++){
                float f = Mathf.clamp(r.dps.items[i] / maxDps);
                if(r.dps.items[i] <= 0f) Draw.color(Pal.remove, r.air ? 0.5f : 0.8f);
                else Draw.color(Tmp.c1.set(Color.orange).lerp(Color.lime, Mathf.sqrt(f)), r.air ? 0.5f : 0.8f);
                Lines.line(r.points.items[i * 2 - 2], r.points.items[i * 2 - 1], r.points.items[i * 2], r.points.items[i * 2 + 1], false);
            }
            if(r.gapFrom >= 0){
                int mid = (r.gapFrom + r.gapTo) / 2;
                float x = r.points.items[mid * 2], y = r.points.items[mid * 2 + 1];
                Draw.color(Pal.remove, 0.9f);
                Lines.stroke(2f * px);
                Lines.circle(x, y, 10f * px + Mathf.absin(Time.time, 8f, 4f * px));
                Drawf.text(Core.bundle.format("client.forecast.gap", r.gapTo - r.gapFrom + 1), x, y + 22f * px, Color.white, px * 3f);
            }
        }

        Building core = player.closestCore();
        if(core != null){
            Color c = load <= 1f ? Pal.heal : Pal.remove;
            Drawf.text(summary(), core.x, core.y + core.block.size * tilesize / 2f + 30f * px, c, px * 3f);
        }
        Draw.reset();
    }
}
