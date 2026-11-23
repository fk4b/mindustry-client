package mindustry.client.fallen;

import arc.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.ai.types.*;
import mindustry.core.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/**
 * Moves friendly AI units that stand on a build plan out of its footprint with an RTS command.
 * Units able to take off are boosted instead: while airborne they stop counting as blockers.
 * Used by {@link mindustry.input.InputHandler#flushPlans} when a plan is invalid only because of units:
 * the plan is still queued and the builders simply wait until the units have stepped aside.
 */
public class UnitClearLogic {
    /** unit id -> Time.time of the last move order (ticks: floats cannot hold epoch millis precisely) */
    private static final IntFloatMap lastOrder = new IntFloatMap();
    /** unit id -> Time.time until which squad formation orders must not override the clear command */
    private static final IntFloatMap clearingUntil = new IntFloatMap();
    /** unit id -> failed clearing attempts while the unit keeps blocking the same spot */
    private static final IntIntMap attempts = new IntIntMap();
    /** unit id -> Time.time of the previous clearing attempt (ticks) */
    private static final IntFloatMap lastAttempt = new IntFloatMap();
    /** unit id -> unit position when the previous clearing order was issued */
    private static final IntFloatMap lastUnitX = new IntFloatMap(), lastUnitY = new IntFloatMap();
    /** unit id -> Time.time until which an auto-boosted unit may stay airborne at the latest */
    private static final IntFloatMap boostedUntil = new IntFloatMap();
    private static final float cooldownTicks = 72f; // 1.2 s at 60 ticks/s
    private static final float maxBoostTicks = 1200f; // 20 s
    /** How long a unit is considered "being cleared", so the squad does not pull it back into its slot. */
    private static final float clearingTicks = 240f;
    private static final Vec2 out = new Vec2();
    private static float lastScan;

    /** Loads this class so its update listener is registered even before the first blocked plan appears. */
    public static void init(){
    }

    /** True while the unit has been ordered off a build plan and must not be dragged back by squad logic. */
    public static boolean isClearing(int unitId){
        return Time.time < clearingUntil.get(unitId, -1f);
    }

    static {
        Events.on(EventType.WorldLoadEvent.class, e -> {
            lastOrder.clear();
            clearingUntil.clear();
            attempts.clear();
            lastAttempt.clear();
            lastUnitX.clear();
            lastUnitY.clear();
            boostedUntil.clear();
            lastScan = 0f;
        });

        //safety net for units that wandered onto an already queued plan
        Events.run(EventType.Trigger.update, () -> {
            if(!state.isGame() || player == null || player.dead() || Time.time - lastScan < 20f) return;
            lastScan = Time.time;

            Unit self = player.unit();
            if(self == null) return;
            for(BuildPlan plan : self.plans){
                if(plan == null || plan.block == null || plan.breaking) continue;
                boolean blockedByUnits = !Build.checkNoUnitOverlap(plan.block, plan.x, plan.y);
                if(!blockedByUnits) continue;
                if(!Build.validPlaceIgnoreUnits(plan.block, player.team(), plan.x, plan.y, plan.rotation, true, true)){
                    continue;
                }
                clearPlan(plan);
            }

            maintainBoosts();
        });
    }

    /**
     * Commands every friendly AI unit blocking the plan footprint to a free tile around it.
     * A unit counts as blocking exactly when the game counts it: grounded, not a leg-stepping unit,
     * and its tile hitbox (the one build checks use) overlaps the footprint. Player-controlled units
     * and units whose controller is not {@link CommandAI} cannot be ordered away.
     *
     * @return true when every blocker was either ordered aside or is already handled; false when the
     * plan stays blocked by an enemy, an uncommandable unit, or units with no free spot around it.
     */
    public static boolean clearPlan(BuildPlan plan) {
        if(plan == null || plan.block == null || plan.breaking || !state.isGame() || player == null || player.dead()) return false;

        Unit self = player.unit();
        if(self == null) return false;

        //nothing to clear: let vanilla deal with the plan
        if(Build.checkNoUnitOverlap(plan.block, plan.x, plan.y)) return true;

        float size = plan.block.size * tilesize;
        float cx = plan.drawx(), cy = plan.drawy();
        float x = cx - size / 2f, y = cy - size / 2f;

        Seq<Unit> blockers = new Seq<>();
        boolean[] enemy = {false}, uncommandable = {false};
        Units.nearby(null, x, y, size, size, unit -> {
            if(!blocksBuild(unit, x, y, size)) return;
            if(unit.team != player.team()){
                enemy[0] = true;
                return;
            }
            if(!(unit.controller() instanceof CommandAI)){
                uncommandable[0] = true;
                return;
            }
            blockers.addUnique(unit);
        });

        //an enemy or a player-controlled unit cannot be ordered away - the plan simply cannot be built yet
        boolean handled = !enemy[0] && !uncommandable[0];
        if(blockers.isEmpty()) return handled;

        Seq<Vec2> reserved = new Seq<>();
        for(Unit unit : blockers){
            //units that can take off simply fly over the plan - while airborne they are not blockers.
            //stance state is not synced to clients, so rely on our own bookkeeping instead of ai.hasStance()
            boolean tracked = boostedUntil.containsKey(unit.id);
            boolean boosted = false;
            if(!tracked && unit.type.canBoost && unit.isGrounded() && unit.type.allowStance(unit, UnitStance.boost)){
                boostedUntil.put(unit.id, Time.time + maxBoostTicks);
                Call.setUnitStance(player, new int[]{unit.id}, UnitStance.boost, true);
                boosted = true;
            }

            //ticks, not epoch millis: floats cannot represent Time.millis() precisely and the cooldown would never expire
            if(Time.time - lastOrder.get(unit.id, -1e9f) < cooldownTicks){
                continue;
            }

            //a unit that barely moved since the last order is stuck: send it further out next time
            boolean stuck = lastAttempt.containsKey(unit.id)
                && Time.time - lastAttempt.get(unit.id, -1e9f) < 360f
                && unit.dst(lastUnitX.get(unit.id, unit.x), lastUnitY.get(unit.id, unit.y)) < tilesize / 2f;
            int failed = stuck ? attempts.get(unit.id, 0) : 0;

            if(!findFreeSpot(unit, cx, cy, size, reserved, failed * tilesize)){
                //an airborne unit no longer blocks the plan, so a missing ground spot is fine for it
                if(!boosted && !tracked) handled = false;
                continue;
            }

            attempts.put(unit.id, Math.min(failed + 1, 6));
            lastAttempt.put(unit.id, Time.time);
            lastUnitX.put(unit.id, unit.x);
            lastUnitY.put(unit.id, unit.y);
            lastOrder.put(unit.id, Time.time);
            clearingUntil.put(unit.id, Time.time + clearingTicks);
            reserved.add(new Vec2(out.x, out.y));
            Call.commandUnits(player, new int[]{unit.id}, null, null, new Vec2(out.x, out.y), false, true);
        }

        return handled;
    }

    /** Mirrors the predicate used by {@link Build#checkNoUnitOverlap}: tile hitbox of grounded, non-leg units. */
    private static boolean blocksBuild(Unit unit, float x, float y, float size) {
        if(unit == null || unit.dead || !unit.isValid() || !unit.isGrounded() || unit.type.allowLegStep) return false;

        unit.hitboxTile(Tmp.r3);
        return Tmp.r3.overlaps(x, y, size, size);
    }

    /** Finds a tile where the unit's tile hitbox clears the plan footprint and that is not inside another queued plan. */
    private static boolean findFreeSpot(Unit unit, float cx, float cy, float planSize, Seq<Vec2> reserved, float extraClearance) {
        int centerX = World.toTile(cx), centerY = World.toTile(cy);
        float half = planSize / 2f;
        //same box the build check uses, so a unit ordered here really stops blocking
        float tileHalf = Math.min(unit.hitSize * 0.66f, 7.8f) / 2f;
        //CommandAI treats a position as reached once the unit is within max(5, hitSize / 2) of it,
        //so the ordered tile has to be that much farther out - otherwise the unit stops while still overlapping.
        float stopTolerance = Math.max(5f, unit.hitSize / 2f);
        float clearance = half + tileHalf + stopTolerance + 2f + extraClearance;
        Unit self = player.unit();

        //nearest ring first, so the unit steps just outside the footprint
        for(int radius = 1; radius <= 16; radius++) {
            for(int dx = -radius; dx <= radius; dx++) {
                for(int dy = -radius; dy <= radius; dy++) {
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;

                    Tile tile = world.tile(centerX + dx, centerY + dy);
                    if(tile == null) continue;
                    if(unit.isGrounded() && (tile.solid() || tile.floor().isDeep())) continue;
                    if(!unit.canPass(tile.x, tile.y)) continue;

                    float wx = tile.worldx(), wy = tile.worldy();
                    if(Math.abs(wx - cx) < clearance && Math.abs(wy - cy) < clearance) continue;
                    //already there: such an order would be a no-op and the unit would keep blocking
                    if(unit.within(wx, wy, stopTolerance + 2f)) continue;
                    if(insideAnyPlan(wx, wy)) continue;
                    if(self != null && self.within(wx, wy, self.hitSize / 2f + unit.hitSize / 2f)) continue;
                    if(reserved.contains(v -> v.within(wx, wy, unit.hitSize))) continue;

                    out.set(wx, wy);
                    return true;
                }
            }
        }

        return false;
    }

    /** Lands auto-boosted units once no queued plan is under them any more (or the boost times out). */
    private static void maintainBoosts() {
        if(boostedUntil.isEmpty()) return;

        IntSeq toLand = new IntSeq();
        for(IntFloatMap.Entry e : boostedUntil.entries()){
            Unit unit = Groups.unit.getByID(e.key);
            if(unit == null || unit.dead || !unit.isValid()){
                toLand.add(e.key);
                continue;
            }
            if(Time.time < e.value && underAnyPlan(unit)) continue;
            toLand.add(e.key);
        }

        for(int i = 0; i < toLand.size; i++){
            int id = toLand.get(i);
            boostedUntil.remove(id, 0f);
            Call.setUnitStance(player, new int[]{id}, UnitStance.boost, false);
        }
    }

    private static boolean underAnyPlan(Unit unit) {
        Unit self = player == null ? null : player.unit();
        if(self == null) return false;

        for(BuildPlan plan : self.plans){
            if(plan == null || plan.block == null || plan.breaking) continue;
            if(plan.bounds(Tmp.r2).grow(unit.hitSize / 2f).contains(unit.x, unit.y)) return true;
        }
        return false;
    }

    private static boolean insideAnyPlan(float x, float y) {
        Unit unit = player == null ? null : player.unit();
        if(unit == null) return false;

        for(BuildPlan plan : unit.plans) {
            if(plan != null && plan.bounds(Tmp.r2).contains(x, y)) return true;
        }
        return false;
    }
}
