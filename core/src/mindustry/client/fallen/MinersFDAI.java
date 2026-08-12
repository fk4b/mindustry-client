package mindustry.client.fallen;

import arc.Core;
import arc.Events;
import arc.math.geom.Vec2;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Interval;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.ai.ItemUnitStance;
import mindustry.ai.UnitCommand;
import mindustry.ai.UnitStance;
import mindustry.ai.types.CommandAI;
import mindustry.client.ui.PanelFragment;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.entities.Units;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.world.blocks.units.RepairTower;
import mindustry.world.blocks.units.RepairTurret;
import mindustry.world.meta.BlockFlag;

import static mindustry.Vars.control;
import static mindustry.Vars.player;

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

    private static boolean wasAutoMiningActive = false;
    private static boolean forceAssignNext = false;

    private static final Interval miningTimer = new Interval();
    private static final Interval assistTimer = new Interval();
    private static final Interval unitRepairTimer = new Interval();

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
    private static final Vec2 tmpMove = new Vec2();

    /** Ores this AI can assign (order used for display / fallback). */
    private static final Item[] MINE_ITEMS = {
        Items.copper, Items.lead, Items.titanium, Items.sand, Items.coal, Items.scrap
    };

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
        PanelFragment.autoHealMegas = Core.settings.getBool("fd-megaAutoHeal", false);
        PanelFragment.autoHealDist = Core.settings.getFloat("fd-megaAutoHealDist", 50f);

        Events.on(EventType.WorldLoadEvent.class, e -> resetState());

        Events.run(EventType.Trigger.update, () -> {
            if (Vars.state.isMenu()) return;
            if (player == null) return;

            // Take over managed units when AI is turned on
            if (autoMiningActive && !wasAutoMiningActive) {
                onActivated();
                wasAutoMiningActive = true;
            } else if (!autoMiningActive) {
                wasAutoMiningActive = false;
            }

            // Build-assist works independently of auto-mining AI
            boolean needAssist = autoAssistBuild;
            if (!autoMiningActive && !needAssist) return;

            pruneDeadUnitIds();

            // ~2×/sec so helpers react quickly when player starts/stops building
            if (needAssist && assistTimer.get(30f)) {
                handleAssistNearPlayer();
            }

            if (!autoMiningActive) return;

            // Self-heal at base repair pads — more frequent than mining rebalance
            if (autoUnitRepair && unitRepairTimer.get(45f)) {
                handleUnitSelfHeal();
            }

            int intervalSec = Math.max(1, PanelFragment.AIMiningUpdateTime);
            if (forceAssignNext || miningTimer.get(intervalSec * 60f)) {
                forceAssignNext = false;
                autoAssignMiningUnitsEqually();
            }
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
        miningTimer.clear();
        assistTimer.clear();
        unitRepairTimer.clear();
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
                    Call.setUnitCommand(player, ids.toArray(), UnitCommand.mineCommand);
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
            Call.setUnitCommand(player, toTakeOver.toArray(), UnitCommand.mineCommand);
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
        Call.setUnitCommand(player, ids.toArray(), UnitCommand.mineCommand);
        for (int i = 0; i < ids.size; i++) {
            lastAiCommand.put(ids.get(i), UnitCommand.mineCommand);
        }
        if (autoMiningActive) forceAssignNext = true;
    }

    /** Drop stale ids so reused unit ids do not inherit manual/AI state. */
    private static void pruneDeadUnitIds() {
        if (manualUnits.isEmpty() && assistingUnits.isEmpty() && lastAiCommand.isEmpty()) return;

        IntSet alive = new IntSet();
        for (Unit u : Groups.unit) {
            if (u.team == player.team()) alive.add(u.id);
        }

        pruneSet(manualUnits, alive);
        pruneSet(assistingUnits, alive);
        pruneSet(healingUnits, alive);

        // ObjectMap has no removeIf on keys in older Arc — collect then remove
        IntSeq deadKeys = new IntSeq();
        for (var e : lastAiCommand.entries()) {
            if (!alive.contains(e.key)) deadKeys.add(e.key);
        }
        for (int i = 0; i < deadKeys.size; i++) {
            int id = deadKeys.get(i);
            lastAiCommand.remove(id);
            lastAiItem.remove(id);
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
                if (shouldBoostForHeal(u)) {
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
                if (shouldBoostForHeal(u)) {
                    needBoost.add(u.id);
                }
            }
        }

        // Issue move orders (grouped by pad)
        for (var e : moveBatches.entries()) {
            Building pad = e.key;
            int[] ids = e.value.toArray();
            if (ids.length == 0) continue;
            tmpMove.set(pad.x, pad.y);
            // Foo client Call: (player, ids, build, unit, pos, queue, stopWhenInRange-ish)
            Call.commandUnits(player, ids, null, null, tmpMove, false, true);
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
                Call.setUnitStance(player, unique.toArray(), UnitStance.boost, true);
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
            Call.setUnitStance(player, unboost.toArray(), UnitStance.boost, false);
        }

        Call.setUnitCommand(player, arr, UnitCommand.mineCommand);
        ObjectMap<Item, IntSeq> resume = new ObjectMap<>();
        for (int id : arr) {
            lastAiCommand.put(id, UnitCommand.mineCommand);
            Item prev = lastAiItem.get(id);
            if (prev != null) {
                if (!resume.containsKey(prev)) resume.put(prev, new IntSeq());
                resume.get(prev).add(id);
            }
        }
        if (!resume.isEmpty()) {
            sendOreStances(resume);
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
            Call.setUnitCommand(player, ids, UnitCommand.assistCommand);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.assistCommand);
                // Keep lastAiItem so after assist they resume the same ore
            }
        }

        if (toReturn.size > 0) {
            int[] ids = toReturn.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.mineCommand);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.mineCommand);
            }
            // Ore rebalance only when auto-mining AI is active
            if (autoMiningActive) {
                ObjectMap<Item, IntSeq> resume = new ObjectMap<>();
                for (int id : ids) {
                    Item prev = lastAiItem.get(id);
                    if (prev != null) {
                        if (!resume.containsKey(prev)) resume.put(prev, new IntSeq());
                        resume.get(prev).add(id);
                    }
                }
                if (!resume.isEmpty()) {
                    sendOreStances(resume);
                }
                forceAssignNext = true;
            }
        }
    }

    public static void autoAssignMiningUnitsEqually() {
        if (player == null || player.unit() == null) return;
        Building core = player.team().core();
        if (core == null) return;

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

        boolean[] flags = {
                PanelFragment.minecopper, PanelFragment.minelead, PanelFragment.minetitan,
                PanelFragment.minesand, PanelFragment.minecoal, PanelFragment.minescrap
        };
        ObjectMap<Item, Float> itemWeights = new ObjectMap<>();
        Seq<Item> allEnabled = new Seq<>();
        float totalWeight = 0;

        Seq<Item> flagged = new Seq<>();
        for (int i = 0; i < MINE_ITEMS.length; i++) {
            if (flags[i]) flagged.add(MINE_ITEMS[i]);
        }
        // Prefer ores present on the map; if indexer reports none yet, fall back to all flagged
        Seq<Item> present = new Seq<>();
        if (Vars.indexer != null) {
            for (Item it : flagged) {
                try {
                    if (Vars.indexer.hasOre(it) || Vars.indexer.hasWallOre(it)) present.add(it);
                } catch (Throwable ignored) {}
            }
        }
        Seq<Item> use = present.isEmpty() ? flagged : present;

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
        if (allEnabled.isEmpty() || totalWeight <= 0) return;

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
                Call.setUnitCommand(player, toReleaseAsAssist.toArray(), UnitCommand.assistCommand);
            }
            if (toReleaseAsMine.size > 0) {
                Call.setUnitCommand(player, toReleaseAsMine.toArray(), UnitCommand.mineCommand);
            }
        }
        if (toForceRestore.size > 0) {
            int[] ids = toForceRestore.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.mineCommand);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.mineCommand);
                lastAiItem.remove(id);
            }
            // They are also in unitGroups and will receive stances this tick
        }
        // Pull megas off heal before any repair packet this tick (auto-heal off / no need)
        if (toPullFromHeal.size > 0) {
            int[] ids = toPullFromHeal.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.mineCommand);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.mineCommand);
                lastAiItem.remove(id);
            }
        }
        // Hard gate: never send repair if the toggle is off (or nothing to heal near cores)
        if (toRepair.size > 0 && PanelFragment.autoHealMegas && needsRepairNearCore) {
            int[] ids = toRepair.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.repairCommand);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.repairCommand);
                lastAiItem.remove(id);
            }
        }

        if (unitGroups.isEmpty()) return;

        for (var entry : unitGroups.entries()) {
            UnitType type = entry.key;
            Seq<Unit> units = entry.value;
            // Stable order so assignment is deterministic across ticks
            units.sort(u -> u.id);

            Seq<Item> possible = allEnabled.select(it -> type.mineTier >= it.hardness);
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
     */
    private static boolean needsExclusiveOreStance(Unit u, Item want) {
        if (!(u.controller() instanceof CommandAI cai)) return true;
        if (cai.command != UnitCommand.mineCommand) return true;
        if (cai.hasStance(UnitStance.mineAuto)) return true;
        UnitStance wantSt = ItemUnitStance.getByItem(want);
        if (wantSt == null || !cai.hasStance(wantSt)) return true;
        for (Item it : MINE_ITEMS) {
            if (it == want) continue;
            UnitStance st = ItemUnitStance.getByItem(it);
            if (st != null && cai.hasStance(st)) return true;
        }
        return false;
    }

    /** Apply exclusive ore stances (clear mineAuto + all other items first). */
    private static void sendOreStances(ObjectMap<Item, IntSeq> toBatchSend) {
        if (toBatchSend.isEmpty() || player == null) return;

        for (var entry : toBatchSend.entries()) {
            int[] ids = entry.value.toArray();
            if (ids.length == 0) continue;
            Item item = entry.key;

            Call.setUnitCommand(player, ids, UnitCommand.mineCommand);
            // Clear auto + every other item so only one ore remains (stances are NOT exclusive by default)
            Call.setUnitStance(player, ids, UnitStance.mineAuto, false);
            for (Item other : MINE_ITEMS) {
                if (other == item) continue;
                UnitStance st = ItemUnitStance.getByItem(other);
                if (st != null) Call.setUnitStance(player, ids, st, false);
            }
            UnitStance stance = ItemUnitStance.getByItem(item);
            if (stance != null) {
                Call.setUnitStance(player, ids, stance, true);
            }

            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.mineCommand);
                lastAiItem.put(id, item);
            }
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

        // Self-heal trip uses moveCommand — never treat as player override
        if (healingUnits.contains(u.id)) {
            return false;
        }
        if (expected == UnitCommand.moveCommand && current == UnitCommand.moveCommand) {
            // stale move after heal ended — reclaim to mine below via commandDiff if needed
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
        for (Item it : MINE_ITEMS) {
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
}
