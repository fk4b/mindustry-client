package mindustry.client.fallen;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Interval;
import arc.util.Strings;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.ItemUnitStance;
import mindustry.ai.UnitCommand;
import mindustry.ai.UnitStance;
import mindustry.ai.types.CommandAI;
import mindustry.client.ui.PanelFragment;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.entities.Units;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.units.RepairTower;
import mindustry.world.blocks.units.RepairTurret;
import mindustry.world.meta.BlockFlag;

import java.util.Arrays;
import java.util.PriorityQueue;

import static mindustry.Vars.control;
import static mindustry.Vars.player;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * Auto-mining AI for commandable miners (mono/poly/pulsar/mega/quasar).
 * <p>
 * Distributes units across enabled ores (even base + demand weights).
 * Item stances are applied exclusively (one ore per unit) — stock Mindustry
 * does not make ItemUnitStances mutually exclusive, so without clearing others
 * every unit keeps copper and all dig the same deposit.
 * Supports mega auto-repair near cores, build-assist near the player,
 * damaged units flying to base repair turrets then resuming mine,
 * and optional respect for player-issued commands (including ore stance changes).
 */
public class MinersFDAI {
    public static boolean autoMiningActive = false;
    public static boolean autoAssistBuild = Core.settings.getBool("AIAssistBuild", false);
    /** Per-type build-assist (builders only — mono has no buildSpeed). Independent of mine toggles. */
    public static boolean assistBuildPoly = Core.settings.getBool("AIAssistPoly", true);
    public static boolean assistBuildPulsar = Core.settings.getBool("AIAssistPulsar", true);
    public static boolean assistBuildMega = Core.settings.getBool("AIAssistMega", true);
    public static boolean assistBuildQuasar = Core.settings.getBool("AIAssistQuasar", true);
    public static boolean respectManualCommands = Core.settings.getBool("AIRespectManual", true);
    public static boolean resetDisabledUnits = Core.settings.getBool("resetDisabledUnits", false);
    /** Send damaged miners to repair point/turret at base, then back to mining. */
    public static boolean autoUnitRepair = Core.settings.getBool("fd-autoUnitRepair", true);
    /** Fraction of max HP below which unit seeks a repair pad (0.8 = 80%). */
    public static float unitRepairGoHp = Core.settings.getFloat("fd-unitRepairGoHp", 0.80f);
    /** Fraction of max HP at/above which unit leaves the pad and mines again. */
    public static float unitRepairDoneHp = Core.settings.getFloat("fd-unitRepairDoneHp", 0.98f);

    public static float AIHelpRad = Core.settings.getFloat("AIHelpRad", 10f);
    /** Avoid enemy turret and armed-unit range (+5 tiles) when auto-mining. */
    public static boolean safeMining = Core.settings.getBool("fd-safeMining", false);

    private static boolean wasAutoMiningActive = false;
    private static boolean forceAssignNext = false;

    private static final Interval miningTimer = new Interval();
    private static final Interval assistTimer = new Interval();
    private static final Interval unitRepairTimer = new Interval();
    private static final Interval safeTimer = new Interval();

    /** Last command this AI issued for a unit. */
    private static final ObjectMap<Integer, UnitCommand> lastAiCommand = new ObjectMap<>();
    /** Last item stance this AI issued (mine only). */
    private static final ObjectMap<Integer, Item> lastAiItem = new ObjectMap<>();
    private static final IntSet manualUnits = new IntSet();
    private static final IntSet assistingUnits = new IntSet();
    /** Units currently ordered to a repair pad (self-heal), not mining. */
    private static final IntSet healingUnits = new IntSet();
    /** Cached repair pads (repair-point / mend projector flag + unit repair tower). */
    private static final Seq<Building> repairPadCache = new Seq<>();

    /** Extra tiles beyond turret range that still count as unsafe. */
    private static final float SAFE_MARGIN_TILES = 5f;
    /** Full-map red-zone rescan interval. */
    private static final long RED_SCAN_MS = 20_000L;
    /** Units we are currently steering around turrets. */
    private static final IntSet routingUnits = new IntSet();
    private static final IntMap<Seq<Vec2>> routingPaths = new IntMap<>();
    private static final IntMap<Tile> routingOre = new IntMap<>();
    private static final IntSet routingToCore = new IntSet();
    private static final ObjectSet<Item> notifiedUnsafeOres = new ObjectSet<>();

    /** Packed tile flags: enemy turret + armed unit range + 5 tiles. Turrets every {@link #RED_SCAN_MS}, units every {@link #UNIT_SCAN_MS}. */
    private static boolean[] red;
    /** Turret-only layer; unit ranges are painted on top of a copy. */
    private static boolean[] turretRed;
    private static int redW, redH;
    private static long redScanAt = 0;
    private static long unitScanAt = 0;
    private static boolean anyRed = false;
    private static boolean turretAnyRed = false;
    private static final long UNIT_SCAN_MS = 1000L;
    /** Team cores ranked by clearance from the red zone (rebuilt with the scan). */
    private static final Seq<Building> rankedCores = new Seq<>();
    private static long rankedCoresAt = 0;
    private static final Vec2 tmpCoreDest = new Vec2();
    /** All ore tiles of each resource that sit outside the red zone. */
    private static final ObjectMap<Item, Seq<Tile>> safeOres = new ObjectMap<>();
    private static int[] astarCame;
    private static float[] astarG;
    private static int[] astarMark;
    private static int astarStamp = 1;

    /** Max AI command/stance packets per window. Vanilla packetSpamLimit is 300/3s. */
    private static final int PACKET_BUDGET = 20;
    private static final long PACKET_WINDOW_MS = 3000L;
    /** Do not re-send exclusive ore stance until the server has had time to apply it. */
    private static final long EXCLUSIVE_RESEND_MS = 2500L;
    private static final int PACKET_QUEUE_MAX = 80;

    private static final Seq<Runnable> packetQueue = new Seq<>();
    private static long packetWindowStart = 0;
    private static int packetsSentThisWindow = 0;
    private static final ObjectMap<Integer, Long> lastExclusiveSentAt = new ObjectMap<>();
    private static final ObjectMap<Long, IntSeq> pendingMoveIds = new ObjectMap<>();
    private static final ObjectMap<Long, Vec2> pendingMovePos = new ObjectMap<>();
    /** Pulsar/quasar boost while we are steering them (they cannot fly without it). */
    private static final IntSet routingBoosted = new IntSet();
    private static final IntSeq pendingBoost = new IntSeq();
    /** Finished a safe route to ore; waiting for mine command to stick. Not a player override. */
    private static final IntSet pendingMineHandoff = new IntSet();

    /** Ores this AI can assign (order used for display / fallback). */
    private static Item[] mineItems(){
        return PanelFragment.allMineOres();
    }

    /** Max share of a type-group that may sit on a single non-crisis ore (spread). */
    private static final float MAX_SINGLE_ORE_SHARE = 0.55f;
    /** In crisis, at most this share of the fleet goes to crisis ores; rest stay spread. */
    private static final float CRISIS_FLEET_SHARE = 0.65f;

    private static boolean inited = false;

    public static void init() {
        if (inited) return;
        inited = true;

        // Reload settings in case class was loaded before settings were ready
        autoAssistBuild = Core.settings.getBool("AIAssistBuild", false);
        assistBuildPoly = Core.settings.getBool("AIAssistPoly", true);
        assistBuildPulsar = Core.settings.getBool("AIAssistPulsar", true);
        assistBuildMega = Core.settings.getBool("AIAssistMega", true);
        assistBuildQuasar = Core.settings.getBool("AIAssistQuasar", true);
        respectManualCommands = Core.settings.getBool("AIRespectManual", true);
        resetDisabledUnits = Core.settings.getBool("resetDisabledUnits", false);
        AIHelpRad = Core.settings.getFloat("AIHelpRad", 10f);
        autoUnitRepair = Core.settings.getBool("fd-autoUnitRepair", true);
        unitRepairGoHp = Core.settings.getFloat("fd-unitRepairGoHp", 0.80f);
        unitRepairDoneHp = Core.settings.getFloat("fd-unitRepairDoneHp", 0.98f);
        safeMining = Core.settings.getBool("fd-safeMining", false);
        PanelFragment.autoHealMegas = Core.settings.getBool("fd-megaAutoHeal", false);
        PanelFragment.autoHealDist = Core.settings.getFloat("fd-megaAutoHealDist", 50f);

        Events.on(EventType.WorldLoadEvent.class, e -> resetState());

        Events.run(EventType.Trigger.update, () -> {
            if (Vars.state.isMenu()) return;
            if (player == null) return;

            flushPacketQueue();

            // Take over managed units when AI is turned on
            if (autoMiningActive && !wasAutoMiningActive) {
                onActivated();
                wasAutoMiningActive = true;
            } else if (!autoMiningActive) {
                wasAutoMiningActive = false;
            }

            // Build-assist works independently of auto-mining AI
            boolean needAssist = autoAssistBuild;
            if (!autoMiningActive && !needAssist) {
                flushPacketQueue();
                return;
            }

            pruneDeadUnitIds();

            // ~0.7×/sec — faster than this burns command packets and gets kicked
            if (needAssist && assistTimer.get(90f)) {
                handleAssistNearPlayer();
            }

            if (autoMiningActive) {
                // Self-heal at base repair pads — more frequent than mining rebalance
                if (autoUnitRepair && unitRepairTimer.get(45f)) {
                    handleUnitSelfHeal();
                }

                int intervalSec = Math.max(1, PanelFragment.AIMiningUpdateTime);
                if (forceAssignNext || miningTimer.get(intervalSec * 60f)) {
                    if (autoAssignMiningUnitsEqually()) {
                        forceAssignNext = false;
                    }
                }

                // After assign, so newly issued mine commands get escorted away from red the same tick
                if (safeMining && safeTimer.get(45f)) {
                    handleSafeMining();
                }
            }

            flushPacketQueue();
        });
    }

    public static void resetState() {
        autoMiningActive = false;
        wasAutoMiningActive = false;
        forceAssignNext = false;
        manualUnits.clear();
        assistingUnits.clear();
        healingUnits.clear();
        repairPadCache.clear();
        lastAiCommand.clear();
        lastAiItem.clear();
        lastExclusiveSentAt.clear();
        miningTimer.clear();
        assistTimer.clear();
        unitRepairTimer.clear();
        safeTimer.clear();
        packetQueue.clear();
        packetsSentThisWindow = 0;
        packetWindowStart = 0;
        pendingMoveIds.clear();
        pendingMovePos.clear();
        pendingMineHandoff.clear();
        clearSafeRouting();
        notifiedUnsafeOres.clear();
    }

    public static void setSafeMining(boolean v) {
        safeMining = v;
        Core.settings.put("fd-safeMining", v);
        if (!v) {
            clearSafeRouting();
        } else {
            redScanAt = 0L;
            forceAssignNext = true;
            safeTimer.clear();
        }
    }

    public static void setAutoUnitRepair(boolean v) {
        autoUnitRepair = v;
        Core.settings.put("fd-autoUnitRepair", v);
        if (!v) {
            // Release any units parked at pads back to mining next tick
            if (!healingUnits.isEmpty()) {
                IntSeq ids = new IntSeq();
                healingUnits.each(ids::add);
                if (ids.size > 0 && player != null) {
                    queueSetCommand(ids.toArray(), UnitCommand.mineCommand);
                    for (int i = 0; i < ids.size; i++) {
                        int id = ids.get(i);
                        lastAiCommand.put(id, UnitCommand.mineCommand);
                    }
                }
                healingUnits.clear();
                forceAssignNext = true;
            }
        } else {
            forceAssignNext = true;
            unitRepairTimer.clear();
        }
    }

    public static void setActive(boolean active) {
        autoMiningActive = active;
        if (active) {
            // Immediate assign on enable (onActivated also runs next frame via was flag)
            forceAssignNext = true;
            miningTimer.clear();
        }
    }

    /** Force a full reassignment on the next update tick (e.g. after toggling auto-heal). */
    public static void forceReassign() {
        forceAssignNext = true;
        miningTimer.clear();
    }

    public static void toggle() {
        setActive(!autoMiningActive);
    }

    public static void setResetDisabledUnits(boolean v) {
        resetDisabledUnits = v;
        Core.settings.put("resetDisabledUnits", v);
    }

    public static void setAutoAssistBuild(boolean v) {
        autoAssistBuild = v;
        Core.settings.put("AIAssistBuild", v);
        if (!v) {
            // Release units we put on assist so they do not stay stuck
            releaseAssistingUnits();
        }
    }

    public static void setAssistBuildPoly(boolean v) {
        assistBuildPoly = v;
        Core.settings.put("AIAssistPoly", v);
    }

    public static void setAssistBuildPulsar(boolean v) {
        assistBuildPulsar = v;
        Core.settings.put("AIAssistPulsar", v);
    }

    public static void setAssistBuildMega(boolean v) {
        assistBuildMega = v;
        Core.settings.put("AIAssistMega", v);
    }

    public static void setAssistBuildQuasar(boolean v) {
        assistBuildQuasar = v;
        Core.settings.put("AIAssistQuasar", v);
    }

    public static void setRespectManualCommands(boolean v) {
        respectManualCommands = v;
        Core.settings.put("AIRespectManual", v);
        if (!v) manualUnits.clear();
    }

    public static void setHelpRad(float tiles) {
        AIHelpRad = tiles;
        Core.settings.put("AIHelpRad", tiles);
    }

    // ================== Command/stance packet budget ==================

    private static void resetBudgetIfNeeded() {
        long now = Time.millis();
        if (packetWindowStart == 0L || now - packetWindowStart >= PACKET_WINDOW_MS) {
            packetWindowStart = now;
            packetsSentThisWindow = 0;
        }
    }

    private static void enqueuePacket(Runnable send) {
        if (send == null) return;
        resetBudgetIfNeeded();
        if (packetsSentThisWindow < PACKET_BUDGET) {
            packetsSentThisWindow++;
            send.run();
            return;
        }
        packetQueue.add(send);
        int drop = packetQueue.size - PACKET_QUEUE_MAX;
        if (drop > 0) {
            packetQueue.removeRange(0, drop - 1);
        }
    }

    private static void flushPacketQueue() {
        resetBudgetIfNeeded();
        while (!packetQueue.isEmpty() && packetsSentThisWindow < PACKET_BUDGET) {
            Runnable r = packetQueue.remove(0);
            packetsSentThisWindow++;
            if (r != null) r.run();
        }
    }

    private static void queueCommandUnits(int[] ids, float x, float y) {
        if (ids == null || ids.length == 0) return;
        int[] copy = Arrays.copyOf(ids, ids.length);
        Vec2 pos = new Vec2(x, y);
        enqueuePacket(() -> {
            if (player == null) return;
            Call.commandUnits(player, copy, null, null, pos, false, true);
        });
    }

    private static void queueSetCommand(int[] ids, UnitCommand cmd) {
        if (ids == null || ids.length == 0 || cmd == null) return;
        int[] copy = Arrays.copyOf(ids, ids.length);
        enqueuePacket(() -> {
            if (player == null) return;
            Call.setUnitCommand(player, copy, cmd);
        });
    }

    private static void queueSetStance(int[] ids, UnitStance stance, boolean on) {
        if (ids == null || ids.length == 0 || stance == null) return;
        int[] copy = Arrays.copyOf(ids, ids.length);
        enqueuePacket(() -> {
            if (player == null) return;
            Call.setUnitStance(player, copy, stance, on);
        });
    }

    private static boolean recentlySentExclusive(int id, Item want) {
        if (want == null || lastAiItem.get(id) != want) return false;
        Long at = lastExclusiveSentAt.get(id);
        return at != null && Time.timeSinceMillis(at) < EXCLUSIVE_RESEND_MS;
    }

    private static void stampExclusive(int[] ids, Item item) {
        long now = Time.millis();
        for (int id : ids) {
            lastAiCommand.put(id, UnitCommand.mineCommand);
            lastAiItem.put(id, item);
            lastExclusiveSentAt.put(id, now);
        }
    }

    private static void onActivated() {
        healingUnits.clear();
        IntSeq toTakeOver = new IntSeq();
        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable()) continue;
            if (!isManagedMinerType(u.type)) continue;

            toTakeOver.add(u.id);
            manualUnits.remove(u.id);
            assistingUnits.remove(u.id);
            healingUnits.remove(u.id);
            lastAiCommand.remove(u.id);
            lastAiItem.remove(u.id);
        }

        if (toTakeOver.size > 0) {
            queueSetCommand(toTakeOver.toArray(), UnitCommand.mineCommand);
            for (int i = 0; i < toTakeOver.size; i++) {
                lastAiCommand.put(toTakeOver.get(i), UnitCommand.mineCommand);
            }
        }

        forceAssignNext = true;
        miningTimer.clear();
        assistTimer.clear();
    }

    private static boolean isManagedMinerType(UnitType type) {
        if (type == UnitTypes.mono) return PanelFragment.mineMonos;
        if (type == UnitTypes.poly) return PanelFragment.minePolys;
        if (type == UnitTypes.mega) return PanelFragment.mineMegas;
        if (type == UnitTypes.pulsar) return PanelFragment.minePulss;
        if (type == UnitTypes.quasar) return PanelFragment.mineQuazs;
        return false;
    }

    /** Builder types allowed to help construct near the player (not mono). */
    private static boolean isAssistBuilderType(UnitType type) {
        if (type == null || type.buildSpeed <= 0f) return false;
        if (type == UnitTypes.poly) return assistBuildPoly;
        if (type == UnitTypes.pulsar) return assistBuildPulsar;
        if (type == UnitTypes.mega) return assistBuildMega;
        if (type == UnitTypes.quasar) return assistBuildQuasar;
        return false;
    }

    /** Send currently assisting units back to mine and clear the set. */
    private static void releaseAssistingUnits() {
        if (assistingUnits.isEmpty() || player == null) {
            assistingUnits.clear();
            return;
        }
        IntSeq ids = new IntSeq();
        assistingUnits.each(ids::add);
        assistingUnits.clear();
        if (ids.size == 0) return;
        queueSetCommand(ids.toArray(), UnitCommand.mineCommand);
        for (int i = 0; i < ids.size; i++) {
            lastAiCommand.put(ids.get(i), UnitCommand.mineCommand);
        }
        if (autoMiningActive) forceAssignNext = true;
    }

    /** Drop stale ids so reused unit ids do not inherit manual/AI state. */
    private static void pruneDeadUnitIds() {
        if (manualUnits.isEmpty() && assistingUnits.isEmpty() && lastAiCommand.isEmpty() && routingUnits.isEmpty() && pendingMineHandoff.isEmpty()) return;

        IntSet alive = new IntSet();
        for (Unit u : Groups.unit) {
            if (u.team == player.team()) alive.add(u.id);
        }

        pruneSet(manualUnits, alive);
        pruneSet(assistingUnits, alive);
        pruneSet(healingUnits, alive);
        pruneSet(routingUnits, alive);
        pruneSet(routingToCore, alive);
        pruneSet(routingBoosted, alive);
        pruneSet(pendingMineHandoff, alive);
        pruneRoutingMaps(alive);

        // ObjectMap has no removeIf on keys in older Arc — collect then remove
        IntSeq deadKeys = new IntSeq();
        for (var e : lastAiCommand.entries()) {
            if (!alive.contains(e.key)) deadKeys.add(e.key);
        }
        for (int i = 0; i < deadKeys.size; i++) {
            int id = deadKeys.get(i);
            lastAiCommand.remove(id);
            lastAiItem.remove(id);
            lastExclusiveSentAt.remove(id);
        }
    }

    // ================== Unit self-heal at base repair pads ==================

    /**
     * Damaged miners fly to the nearest repair-point / mend-projector / unit-repair-tower,
     * wait until HP is restored, then resume mining (previous ore if known).
     */
    private static void handleUnitSelfHeal() {
        if (player == null || player.unit() == null) return;
        if (player.team() == null || player.team().core() == null) return;

        refreshRepairPadCache();
        if (repairPadCache.isEmpty()) {
            // No pads — free anyone stuck in healing set
            if (!healingUnits.isEmpty()) {
                IntSeq free = new IntSeq();
                healingUnits.each(free::add);
                healingUnits.clear();
                resumeMiningAfterHeal(free);
            }
            return;
        }

        IntSeq toResume = new IntSeq();
        // pad → units that need a move order to that pad (batch by position later)
        ObjectMap<Building, IntSeq> moveBatches = new ObjectMap<>();
        // Mechs that can boost (pulsar/quasar): keep boost stance so they FLY to heal, not walk
        IntSeq needBoost = new IntSeq();

        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable()) continue;
            if (!isManagedMinerType(u.type)) continue;
            if (manualUnits.contains(u.id)) continue;

            float hp = u.maxHealth <= 0f ? 1f : u.health / u.maxHealth;
            boolean atPad = healingUnits.contains(u.id);
            Building pad = findNearestRepairPad(u);

            if (atPad) {
                // Healed enough → back to mining
                if (!u.damaged() || hp >= unitRepairDoneHp) {
                    healingUnits.remove(u.id);
                    toResume.add(u.id);
                    continue;
                }
                // Still healing — keep them at the pad (re-path if outside radius)
                if (pad == null) {
                    healingUnits.remove(u.id);
                    toResume.add(u.id);
                    continue;
                }
                float rad = repairRadiusOf(pad);
                // Sit near center; re-issue move if they drifted out
                if (u.dst(pad) > rad * 0.75f) {
                    if (!moveBatches.containsKey(pad)) moveBatches.put(pad, new IntSeq());
                    moveBatches.get(pad).add(u.id);
                }
                // Pulsar/quasar must stay boosted while on pad so repair beam hits them in air
                if (shouldBoostForHeal(u) && !alreadyHasBoost(u)) {
                    needBoost.add(u.id);
                }
                // Drop assist mark so build-assist does not steal them mid-heal
                assistingUnits.remove(u.id);
                continue;
            }

            // Not currently healing — send if low HP
            if (u.damaged() && hp < unitRepairGoHp && pad != null) {
                healingUnits.add(u.id);
                assistingUnits.remove(u.id);
                if (!moveBatches.containsKey(pad)) moveBatches.put(pad, new IntSeq());
                moveBatches.get(pad).add(u.id);
                if (shouldBoostForHeal(u) && !alreadyHasBoost(u)) {
                    needBoost.add(u.id);
                }
            }
        }

        // Issue move orders (grouped by pad)
        for (var e : moveBatches.entries()) {
            Building pad = e.key;
            int[] ids = e.value.toArray();
            if (ids.length == 0) continue;
            queueCommandUnits(ids, pad.x, pad.y);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.moveCommand);
                // keep lastAiItem so resume can restore the same ore
            }
        }

        // Enable boost so mechs (pulsar/quasar) fly to the pad and hover while healing
        if (needBoost.size > 0 && UnitStance.boost != null) {
            // Deduplicate ids
            IntSet seen = new IntSet();
            IntSeq unique = new IntSeq();
            for (int i = 0; i < needBoost.size; i++) {
                int id = needBoost.get(i);
                if (seen.add(id)) unique.add(id);
            }
            if (unique.size > 0) {
                queueSetStance(unique.toArray(), UnitStance.boost, true);
            }
        }

        if (toResume.size > 0) {
            resumeMiningAfterHeal(toResume);
        }
    }

    /** Pulsar/quasar (and any other canBoost miner): heal in the air, not on foot. */
    private static boolean shouldBoostForHeal(Unit u) {
        return u != null && u.type != null && u.type.canBoost;
    }

    private static boolean alreadyHasBoost(Unit u) {
        return u != null && u.controller() instanceof CommandAI cai && cai.hasStance(UnitStance.boost);
    }

    private static void resumeMiningAfterHeal(IntSeq ids) {
        if (ids == null || ids.size == 0 || player == null) return;
        int[] arr = ids.toArray();

        // Land mechs after heal — mining on foot is fine; clear boost stance
        IntSeq unboost = new IntSeq();
        for (int id : arr) {
            Unit u = Groups.unit.getByID(id);
            if (u != null && shouldBoostForHeal(u)) unboost.add(id);
        }
        if (unboost.size > 0 && UnitStance.boost != null) {
            queueSetStance(unboost.toArray(), UnitStance.boost, false);
        }

        ObjectMap<Item, IntSeq> resume = new ObjectMap<>();
        IntSeq leftover = new IntSeq();
        for (int id : arr) {
            lastAiCommand.put(id, UnitCommand.mineCommand);
            Item prev = lastAiItem.get(id);
            if (prev != null) {
                if (!resume.containsKey(prev)) resume.put(prev, new IntSeq());
                resume.get(prev).add(id);
            } else {
                leftover.add(id);
            }
        }
        if (!resume.isEmpty()) {
            sendOreStances(resume);
        }
        if (leftover.size > 0) {
            queueSetCommand(leftover.toArray(), UnitCommand.mineCommand);
        }
        forceAssignNext = true;
    }

    private static void refreshRepairPadCache() {
        repairPadCache.clear();
        if (player == null || player.team() == null) return;

        // Serpulo: repair-point / mend projector (BlockFlag.repair)
        try {
            Seq<Building> flagged = Vars.indexer.getFlagged(player.team(), BlockFlag.repair);
            if (flagged != null) {
                for (Building b : flagged) {
                    if (isUsableRepairPad(b)) repairPadCache.add(b);
                }
            }
        } catch (Throwable ignored) {}

        // Erekir unit-repair-tower (no repair flag)
        for (Building b : Groups.build) {
            if (b == null || b.team != player.team()) continue;
            if (b.block instanceof RepairTower && isUsableRepairPad(b) && !repairPadCache.contains(b)) {
                repairPadCache.add(b);
            }
        }
    }

    private static boolean isUsableRepairPad(Building b) {
        if (b == null || !b.isValid()) return false;
        // Prefer powered pads; still accept unpowered so units can park (power may come back)
        if (b.block instanceof RepairTurret || b.block instanceof RepairTower) {
            return true;
        }
        // Other BlockFlag.repair buildings (modded etc.)
        return b.block != null && b.block.flags != null && b.block.flags.contains(BlockFlag.repair);
    }

    private static float repairRadiusOf(Building b) {
        if (b == null) return 40f;
        if (b.block instanceof RepairTurret rt) return Math.max(24f, rt.repairRadius);
        if (b.block instanceof RepairTower rt) return Math.max(24f, rt.range);
        return 48f;
    }

    private static Building findNearestRepairPad(Unit u) {
        Building best = null;
        float bestD2 = Float.MAX_VALUE;
        // Prefer pads that are actually working (efficiency > 0)
        Building bestAny = null;
        float bestAnyD2 = Float.MAX_VALUE;

        for (int i = 0; i < repairPadCache.size; i++) {
            Building b = repairPadCache.get(i);
            if (!isUsableRepairPad(b)) continue;
            float d2 = u.dst2(b);
            if (d2 < bestAnyD2) {
                bestAnyD2 = d2;
                bestAny = b;
            }
            if (b.efficiency > 0.01f && d2 < bestD2) {
                bestD2 = d2;
                best = b;
            }
        }
        return best != null ? best : bestAny;
    }

    private static void pruneSet(IntSet set, IntSet alive) {
        if (set.isEmpty()) return;
        IntSeq remove = new IntSeq();
        set.each(id -> {
            if (!alive.contains(id)) remove.add(id);
        });
        for (int i = 0; i < remove.size; i++) set.remove(remove.get(i));
    }

    // ================== Build assist near player ==================
    private static void handleAssistNearPlayer() {
        if (player.unit() == null) return;

        boolean building = isPlayerBuilding();
        float px = player.x, py = player.y;
        float radiusPx = AIHelpRad * 8f;

        IntSeq toAssist = new IntSeq();
        IntSeq toReturn = new IntSeq();

        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable()) continue;
            if (!isAssistBuilderType(u.type)) {
                // Type disabled or not a builder — release if we were assisting them
                if (assistingUnits.contains(u.id)) {
                    toReturn.add(u.id);
                    assistingUnits.remove(u.id);
                }
                continue;
            }
            // Only respect manual lock while auto-mining AI is managing units
            if (autoMiningActive && manualUnits.contains(u.id)) continue;
            // Units at repair pads stay there until healed
            if (healingUnits.contains(u.id)) continue;
            if (routingUnits.contains(u.id)) continue;

            boolean inRange = u.dst(px, py) <= radiusPx;
            boolean isCurrentlyAssist = u.controller() instanceof CommandAI cai
                    && cai.command == UnitCommand.assistCommand;

            if (building && inRange) {
                if (!isCurrentlyAssist) toAssist.add(u.id);
                assistingUnits.add(u.id);
            } else if (assistingUnits.contains(u.id)) {
                // Only reclaim assists we own
                toReturn.add(u.id);
                assistingUnits.remove(u.id);
            }
        }

        if (toAssist.size > 0) {
            int[] ids = toAssist.toArray();
            queueSetCommand(ids, UnitCommand.assistCommand);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.assistCommand);
                // Keep lastAiItem so after assist they resume the same ore
            }
        }

        if (toReturn.size > 0) {
            int[] ids = toReturn.toArray();
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.mineCommand);
            }
            if (autoMiningActive) {
                ObjectMap<Item, IntSeq> resume = new ObjectMap<>();
                IntSeq leftover = new IntSeq();
                for (int id : ids) {
                    Item prev = lastAiItem.get(id);
                    if (prev != null) {
                        if (!resume.containsKey(prev)) resume.put(prev, new IntSeq());
                        resume.get(prev).add(id);
                    } else {
                        leftover.add(id);
                    }
                }
                if (!resume.isEmpty()) {
                    sendOreStances(resume);
                }
                if (leftover.size > 0) {
                    queueSetCommand(leftover.toArray(), UnitCommand.mineCommand);
                }
                forceAssignNext = true;
            } else {
                queueSetCommand(ids, UnitCommand.mineCommand);
            }
        }
    }

    public static boolean autoAssignMiningUnitsEqually() {
        if (player == null || player.team() == null) return false;
        if (PanelFragment.enabledOres.isEmpty()) PanelFragment.loadMiningPrefs();
        Building core = player.team().core();
        if (core == null) return false;

        boolean needsRepairNearCore = false;
        if (PanelFragment.autoHealMegas) {
            for (Building ncore : player.team().cores()) {
                if (ncore == null) continue;
                Building nearest = Units.findDamagedTile(player.team(), ncore.x, ncore.y);
                if (nearest != null && nearest.dst(ncore) / 8f < PanelFragment.autoHealDist) {
                    needsRepairNearCore = true;
                    break;
                }
            }
        }

        // CoreBuild.storageCapacity (team.core() is a core building)
        int capacity = core.core() != null ? Math.max(1, core.core().storageCapacity) : 1;

        ObjectMap<Item, Float> itemWeights = new ObjectMap<>();
        Seq<Item> allEnabled = new Seq<>();
        float totalWeight = 0;

        Seq<Item> flagged = new Seq<>();
        for (Item it : mineItems()) {
            if (it != null && PanelFragment.isOreEnabled(it)) flagged.add(it);
        }
        // Prefer ores present on the map; if indexer reports none yet, keep all flagged
        Seq<Item> present = new Seq<>();
        if (Vars.indexer != null) {
            for (Item it : flagged) {
                try {
                    if (Vars.indexer.hasOre(it) || Vars.indexer.hasWallOre(it)) present.add(it);
                } catch (Throwable ignored) {}
            }
        }
        Seq<Item> use = present.isEmpty() ? flagged : present;

        if (safeMining) {
            ensureRedScan(false);
            if (red != null) {
                Seq<Item> unsafe = new Seq<>();
                for (Item it : use) {
                    if (findAnySafeOre(it) == null) unsafe.add(it);
                }
                // Only drop an ore when the scan actually found some other safe deposit —
                // an empty index right after join must not disable everything.
                boolean anySafe = false;
                for (Item it : use) {
                    if (!unsafe.contains(it)) {
                        anySafe = true;
                        break;
                    }
                }
                if (anySafe) {
                    for (Item it : unsafe) {
                        disableMineResource(it, Core.bundle.get("client.fd.safemine.reason.turrets"));
                        use.remove(it);
                    }
                }
            }
        }

        for (Item it : use) {
            allEnabled.add(it);
            float progress = (float) core.items.get(it) / capacity;
            if (Float.isNaN(progress) || Float.isInfinite(progress)) progress = 0f;
            // Mild demand weight — do NOT multiply so hard that one ore eats the fleet
            float weight = Math.max(0.15f, 1.0f - progress);
            if (progress < PanelFragment.crisisThreshold) weight *= 2.2f;
            itemWeights.put(it, weight);
            totalWeight += weight;
        }
        if (allEnabled.isEmpty() || totalWeight <= 0) return false;

        ObjectMap<Item, IntSeq> toBatchSend = new ObjectMap<>();

        // ---- Soft crisis (boost only; never exclusive single-ore mode) ----
        final float CRITICAL_CORE_THRESHOLD = PanelFragment.crisisThreshold / 2f;
        final float NORMAL_CRISIS_THRESHOLD = PanelFragment.crisisThreshold;

        Seq<Item> globalCrisisItems = new Seq<>();
        boolean isCriticalCoreCrisis = false;

        Item[] coreResources = {Items.copper, Items.lead, Items.titanium};
        for (Item it : coreResources) {
            if (!allEnabled.contains(it)) continue;
            float progress = (float) core.items.get(it) / capacity;
            if (progress < CRITICAL_CORE_THRESHOLD) {
                globalCrisisItems.add(it);
                isCriticalCoreCrisis = true;
            }
        }

        if (!isCriticalCoreCrisis) {
            for (Item it : allEnabled) {
                float progress = (float) core.items.get(it) / capacity;
                if (progress < NORMAL_CRISIS_THRESHOLD) {
                    globalCrisisItems.add(it);
                }
            }
        }
        boolean isGlobalCrisis = !globalCrisisItems.isEmpty();
        if (isGlobalCrisis) {
            for (Item it : globalCrisisItems) {
                itemWeights.put(it, itemWeights.get(it, 0.15f) * 2.5f);
            }
        }

        IntSeq toReleaseAsAssist = new IntSeq();
        IntSeq toReleaseAsMine = new IntSeq();
        IntSeq toForceRestore = new IntSeq();
        IntSeq toRepair = new IntSeq();

        // Stable mega order by id so heal/mine split does not thrash
        Seq<Unit> megaUnits = new Seq<>();

        ObjectMap<UnitType, Seq<Unit>> unitGroups = new ObjectMap<>();
        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable()) continue;

            if (!isManagedMinerType(u.type)) {
                if (lastAiCommand.containsKey(u.id) || healingUnits.contains(u.id)) {
                    if (u.type == UnitTypes.poly) {
                        toReleaseAsAssist.add(u.id);
                    } else if (u.type == UnitTypes.mega || u.type == UnitTypes.quasar
                            || u.type == UnitTypes.pulsar || u.type == UnitTypes.mono) {
                        toReleaseAsMine.add(u.id);
                    }
                    lastAiCommand.remove(u.id);
                    lastAiItem.remove(u.id);
                    healingUnits.remove(u.id);
                }
                continue;
            }

            // At repair pad — do not reassign to mine/building-heal until self-heal finishes
            if (healingUnits.contains(u.id)) continue;
            // Safe-mode detour — do not steal the move order
            if (routingUnits.contains(u.id)) continue;

            // Respect player overrides (command and/or mine item stance)
            if (respectManualCommands && u.controller() instanceof CommandAI cai) {
                if (handleManualRespect(u, cai, toForceRestore)) {
                    continue;
                }
            }

            if (manualUnits.contains(u.id) || assistingUnits.contains(u.id)) continue;

            if (u.type == UnitTypes.mega) {
                megaUnits.add(u);
                continue; // handled after sort
            }

            if (u.type.mineTier > 0) {
                if (!unitGroups.containsKey(u.type)) unitGroups.put(u.type, new Seq<>());
                unitGroups.get(u.type).add(u);
            }
        }

        // Megas: stable half-to-heal ONLY when auto-heal toggle is on and damage is near cores.
        // Mega defaultCommand is repair — without an explicit mine command they keep healing.
        IntSeq toPullFromHeal = new IntSeq();
        megaUnits.sort(u -> u.id);
        for (int i = 0; i < megaUnits.size; i++) {
            Unit u = megaUnits.get(i);
            boolean healSlot = PanelFragment.autoHealMegas && needsRepairNearCore
                    && (!isGlobalCrisis || i % 2 == 0);

            if (healSlot) {
                if (!(u.controller() instanceof CommandAI cai && cai.command == UnitCommand.repairCommand)) {
                    toRepair.add(u.id);
                } else {
                    lastAiCommand.put(u.id, UnitCommand.repairCommand);
                    lastAiItem.remove(u.id);
                }
            } else if (u.type.mineTier > 0) {
                // Auto-heal off / no damage: never leave megas on repair (default or leftover AI heal)
                if (u.controller() instanceof CommandAI cai && cai.command == UnitCommand.repairCommand) {
                    toPullFromHeal.add(u.id);
                    lastAiCommand.put(u.id, UnitCommand.mineCommand);
                    lastAiItem.remove(u.id);
                    manualUnits.remove(u.id); // own previous repair packet must not mark as manual forever
                }
                if (!unitGroups.containsKey(u.type)) unitGroups.put(u.type, new Seq<>());
                unitGroups.get(u.type).add(u);
            }
        }

        if (resetDisabledUnits) {
            if (toReleaseAsAssist.size > 0) {
                queueSetCommand(toReleaseAsAssist.toArray(), UnitCommand.assistCommand);
            }
            if (toReleaseAsMine.size > 0) {
                queueSetCommand(toReleaseAsMine.toArray(), UnitCommand.mineCommand);
            }
        }
        if (toForceRestore.size > 0) {
            int[] ids = toForceRestore.toArray();
            queueSetCommand(ids, UnitCommand.mineCommand);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.mineCommand);
                lastAiItem.remove(id);
            }
            // They are also in unitGroups and will receive stances this tick
        }
        // Pull megas off heal before any repair packet this tick (auto-heal off / no need)
        if (toPullFromHeal.size > 0) {
            int[] ids = toPullFromHeal.toArray();
            queueSetCommand(ids, UnitCommand.mineCommand);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.mineCommand);
                lastAiItem.remove(id);
            }
        }
        // Hard gate: never send repair if the toggle is off (or nothing to heal near cores)
        if (toRepair.size > 0 && PanelFragment.autoHealMegas && needsRepairNearCore) {
            int[] ids = toRepair.toArray();
            queueSetCommand(ids, UnitCommand.repairCommand);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.repairCommand);
                lastAiItem.remove(id);
            }
        }

        if (unitGroups.isEmpty()) return false;

        for (var entry : unitGroups.entries()) {
            UnitType type = entry.key;
            Seq<Unit> units = entry.value;
            // Stable order so assignment is deterministic across ticks
            units.sort(u -> u.id);

            Seq<Item> possible = PanelFragment.possibleOresFor(type, allEnabled);
            if (possible.isEmpty()) continue;

            ObjectMap<Item, Integer> quotas = buildOreQuotas(
                units.size, possible, itemWeights, globalCrisisItems, isGlobalCrisis
            );

            // Stickiness: keep units already on a resource that still has quota
            // Prefer live stance; fall back to last AI assignment (packet lag / assist resume)
            Seq<Unit> unassignedUnits = new Seq<>();

            for (Unit u : units) {
                // Never sticky-skip a unit that is still on repair — must get mine + ore stance
                if (u.controller() instanceof CommandAI cai && cai.command == UnitCommand.repairCommand) {
                    unassignedUnits.add(u);
                    continue;
                }

                Item currentItem = getMiningItem(u, possible);
                if (currentItem == null) {
                    Item remembered = lastAiItem.get(u.id);
                    if (remembered != null && possible.contains(remembered)) {
                        currentItem = remembered;
                    }
                }

                if (currentItem != null && quotas.get(currentItem, 0) > 0) {
                    quotas.put(currentItem, quotas.get(currentItem, 0) - 1);
                    lastAiCommand.put(u.id, UnitCommand.mineCommand);
                    lastAiItem.put(u.id, currentItem);
                    // Re-send exclusive stance if unit still has extra item stances / mineAuto
                    if (needsExclusiveOreStance(u, currentItem)) {
                        if (!toBatchSend.containsKey(currentItem)) toBatchSend.put(currentItem, new IntSeq());
                        toBatchSend.get(currentItem).add(u.id);
                    }
                } else {
                    unassignedUnits.add(u);
                }
            }

            for (Unit u : unassignedUnits) {
                Item bestTarget = pickHighestQuota(possible, quotas);
                if (bestTarget == null) {
                    bestTarget = possible.max(it -> itemWeights.get(it, 0f));
                }
                if (bestTarget == null) continue;

                int q = quotas.get(bestTarget, 0);
                if (q > 0) quotas.put(bestTarget, q - 1);

                if (!toBatchSend.containsKey(bestTarget)) toBatchSend.put(bestTarget, new IntSeq());
                toBatchSend.get(bestTarget).add(u.id);
            }
        }

        sendOreStances(toBatchSend);
        return true;
    }

    /**
     * Even base split across ores + demand weights, with soft crisis boost.
     * Guarantees multi-ore spread when the fleet is large enough.
     */
    private static ObjectMap<Item, Integer> buildOreQuotas(
            int unitCount,
            Seq<Item> possible,
            ObjectMap<Item, Float> itemWeights,
            Seq<Item> crisisItems,
            boolean isGlobalCrisis
    ) {
        ObjectMap<Item, Integer> quotas = new ObjectMap<>();
        if (unitCount <= 0 || possible.isEmpty()) return quotas;

        int nOres = possible.size;
        int minPer = PanelFragment.minUnitsPerResource;
        if (minPer > 0 && minPer * nOres > unitCount) {
            minPer = Math.max(0, unitCount / nOres);
        }

        // 1) Even base (round-robin remainder by weight)
        int base = unitCount / nOres;
        int rem = unitCount % nOres;
        Seq<Item> byWeight = possible.copy();
        byWeight.sort((a, b) -> Float.compare(itemWeights.get(b, 0f), itemWeights.get(a, 0f)));

        for (Item it : possible) {
            quotas.put(it, Math.max(minPer, base));
        }
        // Remainder → highest demand first
        for (int i = 0; i < rem; i++) {
            Item it = byWeight.get(i % byWeight.size);
            quotas.put(it, quotas.get(it, 0) + 1);
        }

        // 2) Soft demand rebalance: move a few slots toward needy ores without collapsing spread
        float weightSum = 0f;
        for (Item it : possible) weightSum += itemWeights.get(it, 0f);
        if (weightSum > 0 && unitCount >= nOres) {
            int maxSingle = Math.max(minPer, (int) Math.ceil(unitCount * MAX_SINGLE_ORE_SHARE));
            if (nOres == 1) maxSingle = unitCount;

            // Desired by weight
            ObjectMap<Item, Integer> desired = new ObjectMap<>();
            int desiredSum = 0;
            for (Item it : possible) {
                int d = Math.round((itemWeights.get(it, 0f) / weightSum) * unitCount);
                d = Math.max(minPer, Math.min(maxSingle, d));
                desired.put(it, d);
                desiredSum += d;
            }
            while (desiredSum > unitCount) {
                // Cut highest surplus above minPer
                Item worst = null;
                int worstExtra = -1;
                for (Item it : possible) {
                    int extra = desired.get(it, 0) - minPer;
                    if (extra > worstExtra) {
                        worstExtra = extra;
                        worst = it;
                    }
                }
                if (worst == null || desired.get(worst, 0) <= minPer) break;
                desired.put(worst, desired.get(worst, 0) - 1);
                desiredSum--;
            }
            while (desiredSum < unitCount) {
                Item boost = byWeight.first();
                for (Item it : byWeight) {
                    if (desired.get(it, 0) < maxSingle) {
                        boost = it;
                        break;
                    }
                }
                desired.put(boost, desired.get(boost, 0) + 1);
                desiredSum++;
            }

            // Blend: keep at least even-ish floor, move halfway toward desired
            for (Item it : possible) {
                int even = quotas.get(it, 0);
                int want = desired.get(it, even);
                int blended = (even + want) / 2;
                if (minPer > 0) blended = Math.max(minPer, blended);
                quotas.put(it, blended);
            }
        }

        // 3) Soft crisis: up to CRISIS_FLEET_SHARE on crisis ores; always leave others mining too
        if (isGlobalCrisis && unitCount > 1) {
            Seq<Item> crisisHere = new Seq<>();
            for (Item it : crisisItems) {
                if (possible.contains(it)) crisisHere.add(it);
            }
            if (!crisisHere.isEmpty() && crisisHere.size < possible.size) {
                int nonCrisisOres = possible.size - crisisHere.size;
                // Floor on non-crisis ores so copper crisis never steals 100% of monos
                int keepEach = minPer;
                if (keepEach <= 0 && unitCount >= possible.size) keepEach = 1;
                int maxNonCrisis = unitCount - crisisHere.size; // at least 1 slot per crisis ore
                int nonCrisisBudget = Math.min(maxNonCrisis, nonCrisisOres * Math.max(keepEach, 0));
                // Also enforce: crisis gets at most CRISIS_FLEET_SHARE
                int maxCrisis = Math.max(crisisHere.size, Math.round(unitCount * CRISIS_FLEET_SHARE));
                nonCrisisBudget = Math.max(nonCrisisBudget, unitCount - maxCrisis);

                int given = 0;
                for (Item it : possible) {
                    if (crisisHere.contains(it)) continue;
                    int keep = keepEach;
                    quotas.put(it, keep);
                    given += keep;
                }
                // If over budget, strip non-crisis down
                while (given > nonCrisisBudget) {
                    Item cut = null;
                    for (Item it : possible) {
                        if (!crisisHere.contains(it) && quotas.get(it, 0) > 0) {
                            cut = it;
                            break;
                        }
                    }
                    if (cut == null) break;
                    quotas.put(cut, quotas.get(cut, 0) - 1);
                    given--;
                }
                int left = Math.max(0, unitCount - given);
                int per = left / crisisHere.size;
                int r = left % crisisHere.size;
                for (int i = 0; i < crisisHere.size; i++) {
                    quotas.put(crisisHere.get(i), per + (i < r ? 1 : 0));
                }
            }
        }

        // 4) Normalize sum == unitCount
        int sum = 0;
        for (Item it : possible) sum += quotas.get(it, 0);
        while (sum > unitCount) {
            Item cut = null;
            int best = -1;
            for (Item it : possible) {
                int q = quotas.get(it, 0);
                if (q > best && q > minPer) {
                    best = q;
                    cut = it;
                }
            }
            if (cut == null) {
                for (Item it : possible) {
                    if (quotas.get(it, 0) > 0) {
                        cut = it;
                        break;
                    }
                }
            }
            if (cut == null) break;
            quotas.put(cut, quotas.get(cut, 0) - 1);
            sum--;
        }
        while (sum < unitCount) {
            Item boost = possible.max(it -> itemWeights.get(it, 0f));
            if (boost == null) break;
            quotas.put(boost, quotas.get(boost, 0) + 1);
            sum++;
        }

        return quotas;
    }

    private static Item pickHighestQuota(Seq<Item> possible, ObjectMap<Item, Integer> quotas) {
        Item best = null;
        int bestQ = 0;
        for (Item it : possible) {
            int q = quotas.get(it, 0);
            if (q > bestQ) {
                bestQ = q;
                best = it;
            }
        }
        return bestQ > 0 ? best : null;
    }

    /**
     * True if unit is not exclusively on the given ore stance (mineAuto or other items still on).
     * Skips if we already sent exclusive stance recently — live CommandAI lags behind packets.
     */
    private static boolean needsExclusiveOreStance(Unit u, Item want) {
        if (u == null || want == null) return false;
        if (recentlySentExclusive(u.id, want)) return false;
        if (!(u.controller() instanceof CommandAI cai)) return true;
        if (cai.command != UnitCommand.mineCommand) return true;
        if (cai.hasStance(UnitStance.mineAuto)) return true;
        UnitStance wantSt = ItemUnitStance.getByItem(want);
        if (wantSt == null || !cai.hasStance(wantSt)) return true;
        for (Item it : mineItems()) {
            if (it == want) continue;
            UnitStance st = ItemUnitStance.getByItem(it);
            if (st != null && cai.hasStance(st)) return true;
        }
        return false;
    }

    /**
     * Apply exclusive ore stances. Only sends packets that are actually needed:
     * mine command if not already mining, mineAuto off if on, extra item stances
     * that units still have, and the wanted ore if missing. Recently-sent
     * exclusive assignments are skipped (server ack lag).
     */
    private static void sendOreStances(ObjectMap<Item, IntSeq> toBatchSend) {
        if (toBatchSend.isEmpty() || player == null) return;

        for (var entry : toBatchSend.entries()) {
            Item item = entry.key;
            if (item == null || entry.value.size == 0) continue;

            IntSeq need = new IntSeq();
            for (int i = 0; i < entry.value.size; i++) {
                int id = entry.value.get(i);
                Unit u = Groups.unit.getByID(id);
                if (u == null) continue;
                boolean notMining = !(u.controller() instanceof CommandAI cai && cai.command == UnitCommand.mineCommand);
                // Exclusive cooldown must not skip a move→mine handoff after a safe route
                if (!notMining && recentlySentExclusive(id, item)) continue;
                if (!notMining && !needsExclusiveOreStance(u, item)) {
                    lastAiCommand.put(id, UnitCommand.mineCommand);
                    lastAiItem.put(id, item);
                    pendingMineHandoff.remove(id);
                    continue;
                }
                need.add(id);
            }
            if (need.size == 0) continue;

            int[] ids = need.toArray();
            boolean anyNeedCommand = false;
            boolean anyAuto = false;
            boolean anyMissingWanted = false;
            Item[] items = mineItems();
            boolean[] extraOn = new boolean[items.length];
            UnitStance wantSt = ItemUnitStance.getByItem(item);

            for (int id : ids) {
                Unit u = Groups.unit.getByID(id);
                if (!(u != null && u.controller() instanceof CommandAI cai)) {
                    anyNeedCommand = true;
                    anyAuto = true;
                    anyMissingWanted = true;
                    continue;
                }
                if (cai.command != UnitCommand.mineCommand) anyNeedCommand = true;
                if (cai.hasStance(UnitStance.mineAuto)) anyAuto = true;
                if (wantSt == null || !cai.hasStance(wantSt)) anyMissingWanted = true;
                for (int i = 0; i < items.length; i++) {
                    if (items[i] == item) continue;
                    UnitStance st = ItemUnitStance.getByItem(items[i]);
                    if (st != null && cai.hasStance(st)) extraOn[i] = true;
                }
            }

            if (anyNeedCommand) queueSetCommand(ids, UnitCommand.mineCommand);
            if (anyAuto) queueSetStance(ids, UnitStance.mineAuto, false);
            for (int i = 0; i < items.length; i++) {
                if (!extraOn[i] || items[i] == item) continue;
                UnitStance st = ItemUnitStance.getByItem(items[i]);
                if (st != null) queueSetStance(ids, st, false);
            }
            if (anyMissingWanted && wantSt != null) queueSetStance(ids, wantSt, true);

            stampExclusive(ids, item);
        }
    }

    /**
     * @return true if this unit should be skipped (manual)
     */
    private static boolean handleManualRespect(Unit u, CommandAI cai, IntSeq toForceRestore) {
        UnitCommand current = cai.command;
        UnitCommand expected = lastAiCommand.get(u.id);
        Item expectedItem = lastAiItem.get(u.id);
        Item currentItem = getMiningItem(u, null);

        // Self-heal / safe-route uses moveCommand — never treat as player override
        if (healingUnits.contains(u.id) || routingUnits.contains(u.id) || pendingMineHandoff.contains(u.id)) {
            return false;
        }
        // Stale AI move after routing/heal ended without a mine packet — reclaim
        if (expected == UnitCommand.moveCommand && current == UnitCommand.moveCommand) {
            toForceRestore.add(u.id);
            manualUnits.remove(u.id);
            return false;
        }

        // Mega default / leftover heal while auto-heal is OFF is never a "manual" choice —
        // reclaim immediately so we do not spam-skip and leave them healing buildings.
        if (u.type == UnitTypes.mega
                && current == UnitCommand.repairCommand
                && !PanelFragment.autoHealMegas) {
            toForceRestore.add(u.id);
            manualUnits.remove(u.id);
            return false;
        }

        // Our own repair→mine handoff can look like a player override (lastCommanded = us)
        // while the server still shows repair for a few ticks. Do not mark as manual.
        if (expected == UnitCommand.mineCommand
                && current == UnitCommand.repairCommand
                && !PanelFragment.autoHealMegas) {
            toForceRestore.add(u.id);
            manualUnits.remove(u.id);
            return false;
        }

        boolean commandDiff = expected != null && current != expected;
        boolean itemDiff = expected == UnitCommand.mineCommand
                && current == UnitCommand.mineCommand
                && expectedItem != null
                && (currentItem == null || currentItem != expectedItem);

        if (!commandDiff && !itemDiff) {
            // Back in sync with AI — allow rejoin
            if (manualUnits.contains(u.id) && expected != null
                    && current == expected
                    && (expected != UnitCommand.mineCommand || expectedItem == null || expectedItem == currentItem)) {
                manualUnits.remove(u.id);
            }
            return false;
        }

        // Without lastCommanded we cannot tell who changed the unit — ignore laggy stance/command
        // updates so we do not thrash force-restore every tick after our own packets.
        String cmdr = u.lastCommanded != null ? Strings.stripColors(u.lastCommanded) : "";
        if (cmdr.isEmpty()) return false;

        String myName = Strings.stripColors(player.name);

        if (cmdr.equals(myName)) {
            // Ignore lag after AI-issued mine while unit still reports previous command briefly
            if (expected == current) {
                return false;
            }
            // If AI expected mine and unit still shows repair after we just ordered mine — wait, do not manual-lock
            if (expected == UnitCommand.mineCommand && current == UnitCommand.repairCommand) {
                return false;
            }
            manualUnits.add(u.id);
            return true;
        }

        // Another player overrode our AI unit — reclaim
        if (expected != null) {
            toForceRestore.add(u.id);
            manualUnits.remove(u.id);
            return false;
        }

        return false;
    }

    private static Item getMiningItem(Unit u, Seq<Item> limitTo) {
        if (!(u.controller() instanceof CommandAI cai)) return null;
        if (cai.command != UnitCommand.mineCommand) return null;
        // mineAuto alone → not a specific ore assignment
        if (cai.hasStance(UnitStance.mineAuto)) return null;

        // Prefer remembered exclusive assignment when multiple item stances are still on
        Item remembered = lastAiItem.get(u.id);
        if (remembered != null
                && (limitTo == null || limitTo.contains(remembered))) {
            UnitStance rs = ItemUnitStance.getByItem(remembered);
            if (rs != null && cai.hasStance(rs)) return remembered;
        }

        Item found = null;
        int count = 0;
        for (Item it : mineItems()) {
            if (limitTo != null && !limitTo.contains(it)) continue;
            UnitStance st = ItemUnitStance.getByItem(it);
            if (st != null && cai.hasStance(st)) {
                found = it;
                count++;
            }
        }
        // Only trust exclusive single-ore stance; multi-stance is legacy pollution
        return count == 1 ? found : null;
    }

    /**
     * Player is building: placing, has plans, or actively constructing.
     * Slightly looser than before so assist engages as soon as build mode / plans appear.
     */
    private static boolean isPlayerBuilding() {
        Unit u = player.unit();
        if (u == null) return false;
        if (u.activelyBuilding()) return true;
        if (u.plans != null && u.plans.size > 0) return true;
        return control != null && control.input != null && control.input.isBuilding;
    }

    // ================== Safe mining (avoid enemy turrets + armed units) ==================

    private static void clearSafeRouting() {
        routingUnits.clear();
        routingPaths.clear();
        routingOre.clear();
        routingToCore.clear();
        safeOres.clear();
        red = null;
        turretRed = null;
        redW = redH = 0;
        redScanAt = 0;
        unitScanAt = 0;
        anyRed = false;
        turretAnyRed = false;
        rankedCores.clear();
        rankedCoresAt = 0;
        pendingMoveIds.clear();
        pendingMovePos.clear();
        routingBoosted.clear();
        pendingBoost.clear();
        pendingMineHandoff.clear();
    }

    private static void pruneRoutingMaps(IntSet alive) {
        IntSeq dead = new IntSeq();
        for (var e : routingPaths.entries()) {
            if (!alive.contains(e.key)) dead.add(e.key);
        }
        for (int i = 0; i < dead.size; i++) {
            int id = dead.get(i);
            routingPaths.remove(id);
            routingOre.remove(id);
        }
    }

    private static float safeMarginPx() {
        return SAFE_MARGIN_TILES * tilesize;
    }

    private static int pack(int x, int y) {
        return y * redW + x;
    }

    private static boolean inRedBounds(int x, int y) {
        return red != null && x >= 0 && y >= 0 && x < redW && y < redH;
    }

    private static boolean isRed(int x, int y) {
        return inRedBounds(x, y) && red[pack(x, y)];
    }

    /** True if (x,y) is inside an enemy turret or armed unit range + 5 tiles. */
    public static boolean isPosDangerous(float x, float y) {
        ensureRedScan(false);
        if (red == null || world == null) return false;
        return isRed(world.toTile(x), world.toTile(y));
    }

    public static boolean isTileDangerous(Tile tile) {
        if (tile == null) return false;
        ensureRedScan(false);
        return isRed(tile.x, tile.y);
    }

    /** Full-map red zone + safe-ore index. Turrets at most once per 20s; units every 1s. */
    private static void ensureRedScan(boolean force) {
        if (world == null) return;
        boolean sizeOk = red != null && redW == world.width() && redH == world.height();
        if (force || !sizeOk || Time.timeSinceMillis(redScanAt) >= RED_SCAN_MS) {
            scanRedZones();
            return;
        }
        if (Time.timeSinceMillis(unitScanAt) >= UNIT_SCAN_MS) {
            rescanEnemyUnits();
        }
    }

    private static void scanRedZones() {
        if (world == null) return;
        redW = world.width();
        redH = world.height();
        int n = redW * redH;
        if (red == null || red.length != n) red = new boolean[n];
        else Arrays.fill(red, false);
        anyRed = false;

        Team self = player != null ? player.team() : null;
        float extra = safeMarginPx();

        for (Building b : Groups.build) {
            if (b == null || !b.isValid()) continue;
            if (self != null && (b.team == self || b.team == Team.derelict)) continue;
            if (!(b instanceof BaseTurret.BaseTurretBuild tb)) continue;
            float range = Math.max(tb.range(), ((BaseTurret) tb.block).range) + extra;
            paintRedCircle(b.x, b.y, range);
        }

        if (turretRed == null || turretRed.length != n) turretRed = new boolean[n];
        System.arraycopy(red, 0, turretRed, 0, n);
        turretAnyRed = anyRed;

        paintEnemyUnitZones();

        if (n > 0 && (astarCame == null || astarCame.length != n)) {
            astarCame = new int[n];
            astarG = new float[n];
            astarMark = new int[n];
        }

        rebuildSafeOres();
        rankSafeCores();
        redScanAt = Time.millis();
        unitScanAt = Time.millis();
    }

    /** Re-paint moving enemy units on top of the cached turret layer. */
    private static void rescanEnemyUnits() {
        if (turretRed == null || red == null || turretRed.length != red.length) {
            scanRedZones();
            return;
        }
        System.arraycopy(turretRed, 0, red, 0, red.length);
        anyRed = turretAnyRed;
        paintEnemyUnitZones();
        rebuildSafeOres();
        rankSafeCores();
        unitScanAt = Time.millis();
    }

    private static void paintEnemyUnitZones() {
        Team self = player != null ? player.team() : null;
        float extra = safeMarginPx();
        for (Unit u : Groups.unit) {
            if (u == null || !u.isValid()) continue;
            if (self != null && (u.team == self || u.team == Team.derelict)) continue;
            if (u.type == null || !u.type.hasWeapons()) continue;
            float range = Math.max(u.range(), u.type.maxRange);
            if (range <= 0f) range = u.hitSize;
            paintRedCircle(u.x, u.y, range + extra);
        }
    }

    private static void paintRedCircle(float px, float py, float range) {
        if (range <= 0f) return;
        float r2 = range * range;
        int rTiles = Mathf.ceil(range / tilesize) + 1;
        int cx = world.toTile(px);
        int cy = world.toTile(py);
        for (int dy = -rTiles; dy <= rTiles; dy++) {
            int ty = cy + dy;
            if (ty < 0 || ty >= redH) continue;
            for (int dx = -rTiles; dx <= rTiles; dx++) {
                int tx = cx + dx;
                if (tx < 0 || tx >= redW) continue;
                float twx = tx * tilesize + tilesize / 2f;
                float twy = ty * tilesize + tilesize / 2f;
                if ((twx - px) * (twx - px) + (twy - py) * (twy - py) <= r2) {
                    red[pack(tx, ty)] = true;
                    anyRed = true;
                }
            }
        }
    }

    private static void rebuildSafeOres() {
        safeOres.clear();
        if (world == null) return;
        for (int y = 0; y < redH; y++) {
            for (int x = 0; x < redW; x++) {
                if (isRed(x, y)) continue;
                Tile t = world.tile(x, y);
                if (t == null) continue;
                Item drop = t.block() == Blocks.air ? t.drop() : t.wallDrop();
                if (drop == null || !isTrackedMineItem(drop)) continue;
                Seq<Tile> list = safeOres.get(drop);
                if (list == null) {
                    list = new Seq<>();
                    safeOres.put(drop, list);
                }
                list.add(t);
            }
        }
    }

    private static boolean isTrackedMineItem(Item it) {
        if (it == null) return false;
        for (Item m : mineItems()) if (m == it) return true;
        return false;
    }

    private static boolean segmentHitsRed(float x1, float y1, float x2, float y2) {
        ensureRedScan(false);
        if (red == null || !anyRed) return false;
        float dist = Mathf.dst(x1, y1, x2, y2);
        int steps = Math.max(1, Mathf.ceil(dist / tilesize));
        for (int i = 0; i <= steps; i++) {
            float a = i / (float) steps;
            float x = x1 + (x2 - x1) * a;
            float y = y1 + (y2 - y1) * a;
            if (isRed(world.toTile(x), world.toTile(y))) return true;
        }
        return false;
    }

    private static Tile nearestNonRed(int x, int y) {
        if (inRedBounds(x, y) && !isRed(x, y)) return world.tile(x, y);
        int maxR = Math.max(redW, redH);
        for (int r = 1; r <= Math.min(80, maxR); r++) {
            for (int dx = -r; dx <= r; dx++) {
                Tile a = world.tile(x + dx, y - r);
                if (a != null && !isRed(a.x, a.y)) return a;
                Tile b = world.tile(x + dx, y + r);
                if (b != null && !isRed(b.x, b.y)) return b;
            }
            for (int dy = -r + 1; dy <= r - 1; dy++) {
                Tile a = world.tile(x - r, y + dy);
                if (a != null && !isRed(a.x, a.y)) return a;
                Tile b = world.tile(x + r, y + dy);
                if (b != null && !isRed(b.x, b.y)) return b;
            }
        }
        return null;
    }

    /** Tiles of empty space around (tx, ty) before hitting the red zone. 0 = in/adjacent to danger. */
    private static float tileClearance(int tx, int ty) {
        if (!inRedBounds(tx, ty) || isRed(tx, ty)) return 0f;
        int max = 24;
        for (int r = 1; r <= max; r++) {
            for (int dx = -r; dx <= r; dx++) {
                if (isRed(tx + dx, ty - r) || isRed(tx + dx, ty + r)) return r;
            }
            for (int dy = -r + 1; dy <= r - 1; dy++) {
                if (isRed(tx - r, ty + dy) || isRed(tx + r, ty + dy)) return r;
            }
        }
        return max;
    }

    private static float coreClearance(Building c) {
        return c == null ? 0f : tileClearance(c.tileX(), c.tileY());
    }

    private static void rankSafeCores() {
        rankedCores.clear();
        rankedCoresAt = Time.millis();
        if (player == null || player.team() == null) return;
        for (Building c : player.team().cores()) {
            if (c != null && c.isValid()) rankedCores.add(c);
        }
        rankedCores.sort((a, b) -> Float.compare(coreClearance(b), coreClearance(a)));
    }

    private static void ensureRankedCores() {
        if (rankedCores.isEmpty() || Time.timeSinceMillis(rankedCoresAt) > 1000L) {
            rankSafeCores();
        }
    }

    /** Drop-off point for a core: the core itself, or a nearby non-red tile if the core sits in danger. */
    private static boolean coreSafeDest(Building c, Vec2 out) {
        if (c == null || out == null) return false;
        if (!isPosDangerous(c.x, c.y)) {
            out.set(c.x, c.y);
            return true;
        }
        Tile n = nearestNonRed(c.tileX(), c.tileY());
        if (n == null) return false;
        if (Mathf.dst(c.x, c.y, n.worldx(), n.worldy()) > 12f * tilesize) return false;
        out.set(n.worldx(), n.worldy());
        return true;
    }

    /**
     * Safest team core this unit can actually reach without flying through the red zone.
     * Prefers high clearance (far from turrets/units); among those, the closer one.
     * Falls back to {@code unit.closestCore()} if nothing is safely reachable.
     */
    public static Building findSafestReachableCore(Unit u) {
        if (u == null) {
            return player != null && player.team() != null ? player.team().core() : null;
        }
        if (!safeMining || red == null || !anyRed) return u.closestCore();
        ensureRedScan(false);
        ensureRankedCores();

        Building bestLos = null;
        float bestLosScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < rankedCores.size; i++) {
            Building c = rankedCores.get(i);
            if (c == null || !c.isValid()) continue;
            if (!coreSafeDest(c, tmpCoreDest)) continue;
            float score = coreClearance(c) * 20f * tilesize - u.dst(tmpCoreDest.x, tmpCoreDest.y);
            if (!segmentHitsRed(u.x, u.y, tmpCoreDest.x, tmpCoreDest.y) && score > bestLosScore) {
                bestLosScore = score;
                bestLos = c;
            }
        }
        if (bestLos != null) return bestLos;

        int tries = 0;
        for (int i = 0; i < rankedCores.size && tries < 4; i++) {
            Building c = rankedCores.get(i);
            if (c == null || !c.isValid()) continue;
            if (!coreSafeDest(c, tmpCoreDest)) continue;
            tries++;
            Seq<Vec2> path = astarAvoidRed(u.x, u.y, tmpCoreDest.x, tmpCoreDest.y);
            if (path != null && path.size > 0) return c;
        }
        return u.closestCore();
    }

    private static Vec2 worldPos(int tx, int ty) {
        return new Vec2(tx * tilesize + tilesize / 2f, ty * tilesize + tilesize / 2f);
    }

    private static boolean tileMinesItem(Tile t, Item item, UnitType type) {
        if (t == null || item == null) return false;
        if (type != null) {
            if (type.mineFloor && t.block() == Blocks.air && t.drop() == item) return true;
            if (type.mineWalls && t.block() != Blocks.air && t.wallDrop() == item) return true;
            return false;
        }
        return (t.block() == Blocks.air && t.drop() == item)
                || (t.block() != Blocks.air && t.wallDrop() == item);
    }

    /** Any safe ore tile of this item, or null if every deposit is in the red zone. */
    public static Tile findAnySafeOre(Item item) {
        return findSafeOre(null, item);
    }

    public static Tile findSafeOre(Unit unit, Item item) {
        if (item == null) return null;
        ensureRedScan(false);
        Seq<Tile> list = safeOres.get(item);
        if (list == null || list.isEmpty()) return null;
        UnitType type = unit != null ? unit.type : null;
        Building home = unit != null ? findSafestReachableCore(unit)
                : (player != null && player.team() != null ? player.team().core() : null);
        float hx = home != null ? home.x : (unit != null ? unit.x : 0f);
        float hy = home != null ? home.y : (unit != null ? unit.y : 0f);
        float ux = unit != null ? unit.x : hx;
        float uy = unit != null ? unit.y : hy;

        Tile bestHomeClear = null, bestUnitClear = null, bestHome = null;
        float dHomeClear = Float.MAX_VALUE, dUnitClear = Float.MAX_VALUE, dHome = Float.MAX_VALUE;
        for (int i = 0; i < list.size; i++) {
            Tile t = list.get(i);
            if (!tileMinesItem(t, item, type)) continue;
            if (isTileDangerous(t)) continue;
            float twx = t.worldx(), twy = t.worldy();
            float dh = Mathf.dst2(hx, hy, twx, twy);
            float du = Mathf.dst2(ux, uy, twx, twy);
            boolean clear = unit == null || !segmentHitsRed(ux, uy, twx, twy);
            if (dh < dHome) {
                dHome = dh;
                bestHome = t;
            }
            if (clear && dh < dHomeClear) {
                dHomeClear = dh;
                bestHomeClear = t;
            }
            if (clear && du < dUnitClear) {
                dUnitClear = du;
                bestUnitClear = t;
            }
        }
        if (bestHomeClear != null) return bestHomeClear;
        if (bestUnitClear != null) return bestUnitClear;
        return bestHome;
    }

    /** Used by MinerAI / MinePath when safe mode is on. */
    public static Tile findSafeOreFor(Unit unit, Item item) {
        if (!safeMining) return null;
        return findSafeOre(unit, item);
    }

    public static void disableMineResource(Item item, String reason) {
        if (item == null) return;
        boolean changed = PanelFragment.isOreEnabled(item);
        if (changed) {
            PanelFragment.setOreEnabled(item, false, false);
            forceAssignNext = true;
        }
        if (PanelFragment.itemtomine != null) {
            PanelFragment.itemtomine.remove(item);
        }
        notifyOreUnavailable(item, reason);
    }

    public static void notifyOreUnavailable(Item item, String reason) {
        if (item == null) return;
        if (!notifiedUnsafeOres.add(item)) return;
        String why = reason == null || reason.isEmpty()
                ? Core.bundle.get("client.fd.safemine.reason.turrets")
                : reason;
        String msg = Core.bundle.format("client.fd.safemine.unavailable", item.localizedName, why);
        if (player != null) {
            player.sendMessage(msg);
        } else if (ui != null && ui.chatfrag != null) {
            ui.chatfrag.addMessage(msg, null, null, "", msg);
        }
    }

    /** True if the unit is already on mine command and can (or soon will) dig this dest. */
    private static boolean isActivelyMining(Unit u, Tile dest) {
        if (u == null || !(u.controller() instanceof CommandAI cai)) return false;
        if (cai.command != UnitCommand.mineCommand) return false;
        if (u.mineTile != null && !isTileDangerous(u.mineTile) && u.validMine(u.mineTile)
                && u.within(u.mineTile, Math.max(u.type.mineRange, 24f))) {
            return true;
        }
        return dest != null && u.within(dest, Math.max(u.type.mineRange, 32f));
    }

    private static boolean needsMineHandoff(Unit u) {
        if (u == null) return false;
        if (!(u.controller() instanceof CommandAI cai)) return true;
        return cai.command != UnitCommand.mineCommand;
    }

    /** Retry mine command for units that arrived at ore but are still on move. */
    private static void collectPendingMineHandoffs(IntSeq arrived) {
        if (pendingMineHandoff.isEmpty()) return;
        IntSeq done = new IntSeq();
        pendingMineHandoff.each(id -> {
            Unit u = Groups.unit.getByID(id);
            if (u == null || !u.isValid()) {
                done.add(id);
                return;
            }
            if (!needsMineHandoff(u)) {
                done.add(id);
                return;
            }
            arrived.add(id);
        });
        for (int i = 0; i < done.size; i++) pendingMineHandoff.remove(done.get(i));
    }

    private static void takeLocalMineControl(Unit u, Item item, Tile ore) {
        if (u == null) return;
        if (u.controller() instanceof CommandAI ai) {
            if (ai.command != UnitCommand.mineCommand) {
                ai.command(UnitCommand.mineCommand);
            }
            if (item != null) {
                UnitStance want = ItemUnitStance.getByItem(item);
                if (want != null && !ai.hasStance(want)) {
                    ai.setStance(want, true);
                }
                if (ai.hasStance(UnitStance.mineAuto)) {
                    ai.setStance(UnitStance.mineAuto, false);
                }
            }
            ai.targetPos = null;
        }
        lastAiCommand.put(u.id, UnitCommand.mineCommand);
        if (item != null) lastAiItem.put(u.id, item);
        if (ore != null && !isTileDangerous(ore)
                && u.within(ore, Math.max(u.type.mineRange, 24f)) && u.validMine(ore)) {
            u.mineTile = ore;
        }
    }

    private static void handleSafeMining() {
        if (player == null || player.team() == null) return;
        ensureRedScan(false);

        if (!anyRed) {
            IntSeq done = new IntSeq();
            routingUnits.each(done::add);
            pendingMineHandoff.each(done::add);
            if (done.size > 0) finishRoutingBatch(done);
            return;
        }

        IntSeq arrived = new IntSeq();
        collectPendingMineHandoffs(arrived);

        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable() || !u.isValid()) continue;
            if (!isManagedMinerType(u.type)) continue;
            if (manualUnits.contains(u.id) || assistingUnits.contains(u.id) || healingUnits.contains(u.id)) continue;

            boolean depositing = u.stack.amount >= Math.max(1, u.itemCapacity()) * 0.85f;

            if (routingUnits.contains(u.id)) {
                advanceRouting(u, arrived);
                continue;
            }

            if (pendingMineHandoff.contains(u.id)) continue;

            if (isPosDangerous(u.x, u.y)) {
                extractFromRed(u, depositing, arrived);
                continue;
            }

            Item assigned = lastAiItem.get(u.id);
            if (assigned == null) assigned = getMiningItem(u, null);

            if (depositing) {
                Building core = findSafestReachableCore(u);
                if (core == null) core = u.closestCore();
                if (core == null) continue;
                Building closest = u.closestCore();
                boolean wrongCore = closest != null && closest.id != core.id;
                if (wrongCore || segmentHitsRed(u.x, u.y, core.x, core.y) || isPosDangerous(u.x, u.y)) {
                    startRouteToCore(u, core);
                }
                continue;
            }

            if (assigned == null) continue;

            Tile dest = findSafeOre(u, assigned);
            if (dest == null) {
                disableMineResource(assigned, Core.bundle.get("client.fd.safemine.reason.turrets"));
                Building core = findSafestReachableCore(u);
                if (core == null) core = u.closestCore();
                if (core != null) startRouteToCore(u, core);
                continue;
            }

            if (isActivelyMining(u, dest)) {
                pendingMineHandoff.remove(u.id);
                continue;
            }

            boolean headingBad = false;
            if (u.mineTile != null && isTileDangerous(u.mineTile)) headingBad = true;
            if (u.controller() instanceof CommandAI cai && cai.targetPos != null) {
                if (isPosDangerous(cai.targetPos.x, cai.targetPos.y)
                        || segmentHitsRed(u.x, u.y, cai.targetPos.x, cai.targetPos.y)) {
                    headingBad = true;
                }
            }

            boolean vanillaSafe = false;
            Building core = findSafestReachableCore(u);
            if (core != null && Vars.indexer != null) {
                try {
                    Tile vanilla = u.type.mineFloor ? Vars.indexer.findClosestOre(core.x, core.y, assigned) : null;
                    if ((vanilla == null || isTileDangerous(vanilla)) && u.type.mineWalls) {
                        Tile w = Vars.indexer.findClosestWallOre(core.x, core.y, assigned);
                        if (w != null) vanilla = w;
                    }
                    vanillaSafe = vanilla != null && !isTileDangerous(vanilla)
                            && !segmentHitsRed(u.x, u.y, vanilla.worldx(), vanilla.worldy());
                } catch (Throwable ignored) {}
            }

            boolean atDest = u.within(dest, Math.max(u.type.mineRange, 32f));
            if (atDest || (vanillaSafe && !headingBad)) {
                if (needsMineHandoff(u)) {
                    routingOre.put(u.id, dest);
                    arrived.add(u.id);
                }
                continue;
            }

            startRouteToOre(u, dest, arrived);
        }

        flushPendingMoves();
        if (arrived.size > 0) {
            finishRoutingBatch(arrived);
        }
    }

    /** Pull a unit out of the red zone to the nearest safe ore (or a non-red tile). Never command into red. */
    private static void extractFromRed(Unit u, boolean depositing, IntSeq arrived) {
        if (depositing) {
            Building core = findSafestReachableCore(u);
            if (core == null) core = u.closestCore();
            if (core != null) startRouteToCore(u, core);
            return;
        }
        Item assigned = lastAiItem.get(u.id);
        if (assigned == null) assigned = getMiningItem(u, null);
        Tile dest = assigned != null ? findSafeOre(u, assigned) : null;
        if (dest == null && assigned != null) {
            disableMineResource(assigned, Core.bundle.get("client.fd.safemine.reason.turrets"));
            Building core = findSafestReachableCore(u);
            if (core == null) core = u.closestCore();
            if (core != null) {
                startRouteToCore(u, core);
                return;
            }
        }
        if (dest != null) {
            startRouteToOre(u, dest, arrived);
            return;
        }
        Tile escape = nearestNonRed(world.toTile(u.x), world.toTile(u.y));
        if (escape != null && !isTileDangerous(escape)) {
            Seq<Vec2> path = new Seq<>();
            path.add(new Vec2(escape.worldx(), escape.worldy()));
            beginRoute(u, path);
        }
    }

    private static void startRouteToOre(Unit u, Tile ore, IntSeq arrived) {
        if (ore == null || isTileDangerous(ore)) return;
        if (u.within(ore, Math.max(u.type.mineRange, 32f))) {
            routingOre.put(u.id, ore);
            routingToCore.remove(u.id);
            if (arrived != null) arrived.add(u.id);
            return;
        }
        Seq<Vec2> path = new Seq<>();
        appendSafePath(path, u.x, u.y, ore.worldx(), ore.worldy());
        if (path.isEmpty()) return;
        routingOre.put(u.id, ore);
        routingToCore.remove(u.id);
        beginRoute(u, path);
    }

    private static void startRouteToCore(Unit u, Building core) {
        if (core == null) return;
        float dx = core.x, dy = core.y;
        if (isPosDangerous(dx, dy)) {
            Tile n = nearestNonRed(core.tileX(), core.tileY());
            if (n == null) return;
            dx = n.worldx();
            dy = n.worldy();
        }
        Seq<Vec2> path = new Seq<>();
        appendSafePath(path, u.x, u.y, dx, dy);
        if (path.isEmpty()) return;
        routingToCore.add(u.id);
        routingOre.remove(u.id);
        beginRoute(u, path);
    }

    private static void beginRoute(Unit u, Seq<Vec2> path) {
        if (path == null || path.isEmpty() || player == null) return;
        routingUnits.add(u.id);
        routingPaths.put(u.id, path);
        // Leave mine AI immediately so MinerAI does not keep walking them on foot
        if (u.controller() instanceof CommandAI ai) {
            ai.command(UnitCommand.moveCommand);
            ai.commandPosition(new Vec2(path.first().x, path.first().y));
        }
        requestBoostForRoute(u);
        commandMove(u, path.first().x, path.first().y);
    }

    /** Pulsar/quasar cannot fly unless boost is on — set it locally and send to the server. */
    private static void requestBoostForRoute(Unit u) {
        if (!shouldBoostForHeal(u) || UnitStance.boost == null) return;
        routingBoosted.add(u.id);
        if (u.controller() instanceof CommandAI ai) {
            ai.setStance(UnitStance.boost, true);
        }
        u.updateBoosting(true, true);
        pendingBoost.add(u.id);
    }

    private static void advanceRouting(Unit u, IntSeq arrived) {
        Seq<Vec2> path = routingPaths.get(u.id);
        if (path == null || path.isEmpty()) {
            arrived.add(u.id);
            return;
        }
        // Re-apply boost each tick: commandUnits/mine AI can drop them back to walking
        if (shouldBoostForHeal(u) && !u.isFlying()) {
            requestBoostForRoute(u);
        }
        Tile ore = routingOre.get(u.id);
        if (ore != null && !routingToCore.contains(u.id)
                && !isPosDangerous(u.x, u.y)
                && u.within(ore, Math.max(u.type.mineRange, 32f))) {
            arrived.add(u.id);
            return;
        }
        Vec2 wp = path.first();
        float arrive = Math.max(24f, u.hitSize * 1.2f);
        if (ore != null && !routingToCore.contains(u.id) && path.size == 1) {
            arrive = Math.max(arrive, Math.max(u.type.mineRange * 0.75f, 32f));
        }
        if (u.within(wp.x, wp.y, arrive)) {
            path.remove(0);
            if (path.isEmpty()) {
                arrived.add(u.id);
                return;
            }
            commandMove(u, path.first().x, path.first().y);
            return;
        }
        if (isPosDangerous(wp.x, wp.y)) {
            path.remove(0);
            if (path.isEmpty()) {
                arrived.add(u.id);
                return;
            }
            commandMove(u, path.first().x, path.first().y);
            return;
        }
        if (isPosDangerous(u.x, u.y)) {
            boolean depositing = routingToCore.contains(u.id) || u.stack.amount >= Math.max(1, u.itemCapacity()) * 0.85f;
            extractFromRed(u, depositing, arrived);
        }
    }

    /** Bookkeeping + batched mine/stance packets for units that finished a safe route. */
    private static void finishRoutingBatch(IntSeq arrived) {
        if (arrived == null || arrived.size == 0 || player == null) return;

        IntSet uniq = new IntSet();
        IntSeq unique = new IntSeq();
        for (int i = 0; i < arrived.size; i++) {
            int id = arrived.get(i);
            if (uniq.add(id)) unique.add(id);
        }
        arrived = unique;

        IntSeq leftover = new IntSeq();
        IntSeq leftoverBoost = new IntSeq();
        IntSeq takeLocal = new IntSeq();
        ObjectMap<Item, IntSeq> byOre = new ObjectMap<>();
        IntMap<Tile> arrivedOre = new IntMap<>();
        boolean reassign = false;

        for (int i = 0; i < arrived.size; i++) {
            int id = arrived.get(i);
            routingUnits.remove(id);
            routingPaths.remove(id);
            Tile ore = routingOre.remove(id);
            boolean toCore = routingToCore.remove(id);
            Unit u = Groups.unit.getByID(id);
            if (u == null) continue;

            if (routingBoosted.remove(id) && shouldBoostForHeal(u) && UnitStance.boost != null) {
                leftoverBoost.add(id);
            }

            lastAiCommand.put(id, UnitCommand.mineCommand);
            lastExclusiveSentAt.remove(id);
            if (ore != null && isTileDangerous(ore)) {
                pendingMineHandoff.remove(id);
                continue;
            }
            Item item = lastAiItem.get(id);
            if (item == null && ore != null) {
                item = ore.drop() != null ? ore.drop() : ore.wallDrop();
            }
            pendingMineHandoff.add(id);
            takeLocal.add(id);
            if (ore != null) arrivedOre.put(id, ore);
            if (item != null) {
                if (!byOre.containsKey(item)) byOre.put(item, new IntSeq());
                byOre.get(item).add(id);
            } else {
                leftover.add(id);
            }
            if (toCore) reassign = true;
        }

        if (leftoverBoost.size > 0) {
            queueSetStance(leftoverBoost.toArray(), UnitStance.boost, false);
        }
        if (!byOre.isEmpty()) {
            sendOreStances(byOre);
        }
        if (leftover.size > 0) {
            queueSetCommand(leftover.toArray(), UnitCommand.mineCommand);
        }
        for (int i = 0; i < takeLocal.size; i++) {
            int id = takeLocal.get(i);
            Unit u = Groups.unit.getByID(id);
            takeLocalMineControl(u, lastAiItem.get(id), arrivedOre.get(id));
        }
        if (reassign) forceAssignNext = true;
    }

    private static long moveKey(float x, float y) {
        int tx = Mathf.round(x / tilesize);
        int ty = Mathf.round(y / tilesize);
        return ((long) tx << 32) | (ty & 0xffffffffL);
    }

    private static void commandMove(Unit u, float x, float y) {
        if (u == null) return;
        if (isPosDangerous(x, y)) {
            Tile n = nearestNonRed(world.toTile(x), world.toTile(y));
            if (n == null) return;
            x = n.worldx();
            y = n.worldy();
        }
        long k = moveKey(x, y);
        IntSeq ids = pendingMoveIds.get(k);
        if (ids == null) {
            ids = new IntSeq();
            pendingMoveIds.put(k, ids);
            pendingMovePos.put(k, new Vec2(x, y));
        }
        ids.add(u.id);
        lastAiCommand.put(u.id, UnitCommand.moveCommand);
    }

    private static void flushPendingMoves() {
        int[] boostIds = null;
        if (pendingBoost.size > 0 && UnitStance.boost != null && player != null) {
            IntSet seen = new IntSet();
            IntSeq unique = new IntSeq();
            for (int i = 0; i < pendingBoost.size; i++) {
                int id = pendingBoost.get(i);
                if (seen.add(id)) unique.add(id);
            }
            pendingBoost.clear();
            if (unique.size > 0) {
                boostIds = unique.toArray();
                Call.setUnitStance(player, boostIds, UnitStance.boost, true);
            }
        }
        if (!pendingMoveIds.isEmpty() && player != null) {
            for (var e : pendingMoveIds.entries()) {
                Vec2 pos = pendingMovePos.get(e.key);
                if (pos == null || e.value.size == 0) continue;
                Call.commandUnits(player, e.value.toArray(), null, null, new Vec2(pos.x, pos.y), false, true);
            }
            pendingMoveIds.clear();
            pendingMovePos.clear();
        }
        // Move order can land before stance on the server — send boost again after
        if (boostIds != null) {
            Call.setUnitStance(player, boostIds, UnitStance.boost, true);
        }
    }

    /** Writes waypoints that stay outside the red zone. Adds nothing if no safe path exists. */
    private static void appendSafePath(Seq<Vec2> out, float x1, float y1, float x2, float y2) {
        if (isPosDangerous(x2, y2)) {
            Tile n = nearestNonRed(world.toTile(x2), world.toTile(y2));
            if (n == null) return;
            x2 = n.worldx();
            y2 = n.worldy();
        }
        if (isPosDangerous(x1, y1)) {
            Tile escape = nearestNonRed(world.toTile(x1), world.toTile(y1));
            if (escape == null) return;
            out.add(new Vec2(escape.worldx(), escape.worldy()));
            x1 = escape.worldx();
            y1 = escape.worldy();
        }
        if (!segmentHitsRed(x1, y1, x2, y2)) {
            out.add(new Vec2(x2, y2));
            return;
        }
        Seq<Vec2> found = astarAvoidRed(x1, y1, x2, y2);
        if (found == null || found.isEmpty()) return;
        out.addAll(found);
    }

    private static Seq<Vec2> astarAvoidRed(float x1, float y1, float x2, float y2) {
        if (red == null || world == null || astarCame == null) return null;
        int sx = Mathf.clamp(world.toTile(x1), 0, redW - 1);
        int sy = Mathf.clamp(world.toTile(y1), 0, redH - 1);
        int gx = Mathf.clamp(world.toTile(x2), 0, redW - 1);
        int gy = Mathf.clamp(world.toTile(y2), 0, redH - 1);
        if (isRed(sx, sy)) {
            Tile n = nearestNonRed(sx, sy);
            if (n == null) return null;
            sx = n.x;
            sy = n.y;
        }
        if (isRed(gx, gy)) {
            Tile n = nearestNonRed(gx, gy);
            if (n == null) return null;
            gx = n.x;
            gy = n.y;
        }
        if (sx == gx && sy == gy) {
            Seq<Vec2> s = new Seq<>();
            s.add(worldPos(gx, gy));
            return s;
        }

        astarStamp++;
        if (astarStamp == Integer.MAX_VALUE) {
            Arrays.fill(astarMark, 0);
            astarStamp = 1;
        }

        int start = pack(sx, sy);
        int goal = pack(gx, gy);
        astarCame[start] = -1;
        astarG[start] = 0f;
        astarMark[start] = astarStamp;

        PriorityQueue<int[]> open = new PriorityQueue<>((a, b) -> Float.compare(Float.intBitsToFloat(a[1]), Float.intBitsToFloat(b[1])));
        open.add(new int[]{start, Float.floatToIntBits(octile(sx, sy, gx, gy))});

        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dy = {0, 0, 1, -1, 1, -1, 1, -1};
        int expanded = 0;
        final int limit = 12000;
        boolean found = false;

        while (!open.isEmpty() && expanded < limit) {
            int cur = open.poll()[0];
            int cx = cur % redW, cy = cur / redW;
            expanded++;
            if (cur == goal) {
                found = true;
                break;
            }
            for (int i = 0; i < 8; i++) {
                int nx = cx + dx[i], ny = cy + dy[i];
                if (!inRedBounds(nx, ny) || isRed(nx, ny)) continue;
                if (i >= 4 && (isRed(cx + dx[i], cy) || isRed(cx, cy + dy[i]))) continue;
                int ni = pack(nx, ny);
                float step = i < 4 ? 1f : 1.4142f;
                float ng = astarG[cur] + step;
                if (astarMark[ni] != astarStamp || ng < astarG[ni]) {
                    astarMark[ni] = astarStamp;
                    astarG[ni] = ng;
                    astarCame[ni] = cur;
                    float f = ng + octile(nx, ny, gx, gy);
                    open.add(new int[]{ni, Float.floatToIntBits(f)});
                }
            }
        }
        if (!found) return null;

        Seq<Vec2> raw = new Seq<>();
        for (int cur = goal; cur >= 0; ) {
            raw.add(worldPos(cur % redW, cur / redW));
            cur = astarCame[cur];
        }
        raw.reverse();
        simplifyPath(raw);
        return raw;
    }

    private static float octile(int x1, int y1, int x2, int y2) {
        int dx = Math.abs(x1 - x2), dy = Math.abs(y1 - y2);
        return Math.max(dx, dy) + 0.4142f * Math.min(dx, dy);
    }

    private static void simplifyPath(Seq<Vec2> path) {
        int i = 1;
        while (i < path.size - 1) {
            Vec2 a = path.get(i - 1), c = path.get(i + 1);
            if (!segmentHitsRed(a.x, a.y, c.x, c.y)) {
                path.remove(i);
            } else {
                i++;
            }
        }
    }
}
