package mindustry.client.fallen;

import arc.Core;
import arc.Events;
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

import static mindustry.Vars.control;
import static mindustry.Vars.player;

/**
 * Auto-mining AI for commandable miners (mono/poly/pulsar/mega/quasar).
 * <p>
 * Distributes units by core demand (weights), min quotas, and crisis priorities.
 * Supports mega auto-repair near cores, build-assist near the player, and
 * optional respect for player-issued commands (including ore stance changes).
 */
public class MinersFDAI {
    public static boolean autoMiningActive = false;
    public static boolean autoAssistBuild = Core.settings.getBool("AIAssistBuild", false);
    public static boolean respectManualCommands = Core.settings.getBool("AIRespectManual", true);
    public static boolean resetDisabledUnits = Core.settings.getBool("resetDisabledUnits", false);

    public static float AIHelpRad = Core.settings.getFloat("AIHelpRad", 10f);

    private static boolean wasAutoMiningActive = false;
    private static boolean forceAssignNext = false;

    private static final Interval miningTimer = new Interval();
    private static final Interval assistTimer = new Interval();

    /** Last command this AI issued for a unit. */
    private static final ObjectMap<Integer, UnitCommand> lastAiCommand = new ObjectMap<>();
    /** Last item stance this AI issued (mine only). */
    private static final ObjectMap<Integer, Item> lastAiItem = new ObjectMap<>();
    private static final IntSet manualUnits = new IntSet();
    private static final IntSet assistingUnits = new IntSet();

    private static boolean inited = false;

    public static void init() {
        if (inited) return;
        inited = true;

        // Reload settings in case class was loaded before settings were ready
        autoAssistBuild = Core.settings.getBool("AIAssistBuild", false);
        respectManualCommands = Core.settings.getBool("AIRespectManual", true);
        resetDisabledUnits = Core.settings.getBool("resetDisabledUnits", false);
        AIHelpRad = Core.settings.getFloat("AIHelpRad", 10f);

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

            if (!autoMiningActive) return;

            pruneDeadUnitIds();

            if (autoAssistBuild && assistTimer.get(60f)) {
                handleAssistNearPlayer();
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
        lastAiCommand.clear();
        lastAiItem.clear();
        miningTimer.clear();
        assistTimer.clear();
    }

    public static void setActive(boolean active) {
        autoMiningActive = active;
        if (active) {
            // Immediate assign on enable (onActivated also runs next frame via was flag)
            forceAssignNext = true;
            miningTimer.clear();
        }
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
        IntSeq toTakeOver = new IntSeq();
        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable()) continue;
            if (!isManagedMinerType(u.type)) continue;

            toTakeOver.add(u.id);
            manualUnits.remove(u.id);
            assistingUnits.remove(u.id);
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

    /** Drop stale ids so reused unit ids do not inherit manual/AI state. */
    private static void pruneDeadUnitIds() {
        if (manualUnits.isEmpty() && assistingUnits.isEmpty() && lastAiCommand.isEmpty()) return;

        IntSet alive = new IntSet();
        for (Unit u : Groups.unit) {
            if (u.team == player.team()) alive.add(u.id);
        }

        pruneSet(manualUnits, alive);
        pruneSet(assistingUnits, alive);

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
            if (u.type.buildSpeed <= 0f) continue;
            if (!isManagedMinerType(u.type)) continue;
            if (manualUnits.contains(u.id)) continue;

            boolean inRange = u.dst(px, py) <= radiusPx;
            boolean isCurrentlyAssist = u.controller() instanceof CommandAI cai
                    && cai.command == UnitCommand.assistCommand;

            if (building && inRange) {
                if (!isCurrentlyAssist) toAssist.add(u.id);
                assistingUnits.add(u.id);
            } else if (assistingUnits.contains(u.id) || isCurrentlyAssist) {
                // Only reclaim assists we own (or still marked as assisting)
                if (assistingUnits.contains(u.id)) {
                    toReturn.add(u.id);
                    assistingUnits.remove(u.id);
                }
            }
        }

        if (toAssist.size > 0) {
            int[] ids = toAssist.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.assistCommand);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.assistCommand);
                lastAiItem.remove(id);
            }
        }

        if (toReturn.size > 0) {
            int[] ids = toReturn.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.mineCommand);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.mineCommand);
                lastAiItem.remove(id);
            }
            // Re-run distribution soon so they get item stances immediately
            forceAssignNext = true;
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

        Item[] items = {Items.copper, Items.lead, Items.titanium, Items.sand, Items.coal, Items.scrap};
        boolean[] flags = {
                PanelFragment.minecopper, PanelFragment.minelead, PanelFragment.minetitan,
                PanelFragment.minesand, PanelFragment.minecoal, PanelFragment.minescrap
        };
        ObjectMap<Item, Float> itemWeights = new ObjectMap<>();
        Seq<Item> allEnabled = new Seq<>();
        float totalWeight = 0;

        for (int i = 0; i < items.length; i++) {
            if (!flags[i]) continue;
            Item it = items[i];
            allEnabled.add(it);
            float progress = (float) core.items.get(it) / capacity;
            if (Float.isNaN(progress) || Float.isInfinite(progress)) progress = 0f;
            float weight = Math.max(0.05f, 1.0f - progress);
            if (progress < 0.1f) weight *= 5f;
            itemWeights.put(it, weight);
            totalWeight += weight;
        }
        if (allEnabled.isEmpty() || totalWeight <= 0) return;

        ObjectMap<Item, IntSeq> toBatchSend = new ObjectMap<>();

        // ---- Global crisis ----
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
                if (lastAiCommand.containsKey(u.id)) {
                    if (u.type == UnitTypes.poly) {
                        toReleaseAsAssist.add(u.id);
                    } else if (u.type == UnitTypes.mega || u.type == UnitTypes.quasar
                            || u.type == UnitTypes.pulsar || u.type == UnitTypes.mono) {
                        toReleaseAsMine.add(u.id);
                    }
                    lastAiCommand.remove(u.id);
                    lastAiItem.remove(u.id);
                }
                continue;
            }

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

        // Megas: stable half-to-heal when needed
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
        if (toRepair.size > 0) {
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

            Seq<Item> possible = allEnabled.select(it -> type.mineTier >= it.hardness);
            if (possible.isEmpty()) continue;

            Seq<Item> targets = possible;
            boolean isCrisisMode = false;

            if (isGlobalCrisis) {
                Seq<Item> myCrisisTargets = new Seq<>();
                for (Item it : globalCrisisItems) {
                    if (possible.contains(it)) myCrisisTargets.add(it);
                }
                if (!myCrisisTargets.isEmpty()) {
                    targets = myCrisisTargets;
                    isCrisisMode = true;
                }
            }

            ObjectMap<Item, Integer> quotas = new ObjectMap<>();
            int assignedCount = 0;

            float currentTotalWeight = 0;
            for (Item it : targets) currentTotalWeight += itemWeights.get(it, 0f);
            if (currentTotalWeight <= 0) continue;

            // Cap min-per-resource so quotas can fit the fleet
            int minPer = PanelFragment.minUnitsPerResource;
            if (minPer > 0 && minPer * targets.size > units.size) {
                minPer = Math.max(0, units.size / targets.size);
            }

            for (Item it : possible) {
                int target;

                if (isCrisisMode) {
                    if (!targets.contains(it)) {
                        target = 0;
                    } else {
                        // Even split only — no +1 over-allocation that fighting balance undoes
                        int baseShare = units.size / targets.size;
                        int remainder = units.size % targets.size;
                        int index = targets.indexOf(it);
                        target = baseShare + (index < remainder ? 1 : 0);
                    }
                } else {
                    // Weight only among resources that can actually be mined by this type
                    float typeWeightSum = 0;
                    for (Item p : possible) typeWeightSum += itemWeights.get(p, 0f);
                    if (typeWeightSum <= 0) typeWeightSum = currentTotalWeight;

                    int baseTarget = Math.round((itemWeights.get(it, 0f) / typeWeightSum) * units.size);
                    // min only for items that are in "targets" (all possible in normal mode)
                    target = Math.max(minPer, baseTarget);
                }

                quotas.put(it, target);
                assignedCount += target;
            }

            // Balance down: prefer cutting high quota / low weight
            while (assignedCount > units.size) {
                Item toReduce = possible.max(it -> {
                    int q = quotas.get(it, 0);
                    if (q <= 0) return -1f;
                    // Prefer reducing fuller (lower weight) resources first
                    return q * 1000f + (1f / itemWeights.get(it, 0.05f));
                });
                if (toReduce != null && quotas.get(toReduce, 0) > 0) {
                    quotas.put(toReduce, quotas.get(toReduce, 0) - 1);
                    assignedCount--;
                } else break;
            }

            while (assignedCount < units.size) {
                Item toBoost = targets.max(it -> itemWeights.get(it, 0f));
                if (toBoost != null) {
                    quotas.put(toBoost, quotas.get(toBoost, 0) + 1);
                    assignedCount++;
                } else break;
            }

            // Stickiness: keep units already on a resource that still has quota
            Seq<Unit> unassignedUnits = new Seq<>();

            for (Unit u : units) {
                Item currentItem = getMiningItem(u, possible);

                if (currentItem != null && quotas.get(currentItem, 0) > 0) {
                    quotas.put(currentItem, quotas.get(currentItem, 0) - 1);
                    // Remember AI ownership without re-sending
                    lastAiCommand.put(u.id, UnitCommand.mineCommand);
                    lastAiItem.put(u.id, currentItem);
                } else {
                    unassignedUnits.add(u);
                }
            }

            for (Unit u : unassignedUnits) {
                Item bestTarget = null;
                int bestQ = 0;
                for (Item it : possible) {
                    int q = quotas.get(it, 0);
                    if (q > bestQ) {
                        bestQ = q;
                        bestTarget = it;
                    }
                }

                if (bestTarget != null && bestQ > 0) {
                    quotas.put(bestTarget, bestQ - 1);
                    if (!toBatchSend.containsKey(bestTarget)) toBatchSend.put(bestTarget, new IntSeq());
                    toBatchSend.get(bestTarget).add(u.id);
                } else {
                    // Fallback among crisis targets if in crisis, else all possible
                    Seq<Item> fallbackPool = isCrisisMode ? targets : possible;
                    Item fallback = fallbackPool.max(it -> itemWeights.get(it, 0f));
                    if (fallback == null) continue;

                    if (!(u.controller() instanceof CommandAI cai
                            && cai.command == UnitCommand.mineCommand
                            && cai.hasStance(ItemUnitStance.getByItem(fallback)))) {
                        if (!toBatchSend.containsKey(fallback)) toBatchSend.put(fallback, new IntSeq());
                        toBatchSend.get(fallback).add(u.id);
                    } else {
                        lastAiCommand.put(u.id, UnitCommand.mineCommand);
                        lastAiItem.put(u.id, fallback);
                    }
                }
            }
        }

        for (var entry : toBatchSend.entries()) {
            int[] ids = entry.value.toArray();
            Item item = entry.key;
            Call.setUnitCommand(player, ids, UnitCommand.mineCommand);
            Call.setUnitStance(player, ids, UnitStance.mineAuto, false);
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

        Item[] check = {
                Items.copper, Items.lead, Items.titanium, Items.sand, Items.coal, Items.scrap
        };
        for (Item it : check) {
            if (limitTo != null && !limitTo.contains(it)) continue;
            UnitStance st = ItemUnitStance.getByItem(it);
            if (st != null && cai.hasStance(st)) return it;
        }
        return null;
    }

    /**
     * Player is "building" only when actually constructing or build mode with plans —
     * not merely having stale plans in the queue.
     */
    private static boolean isPlayerBuilding() {
        Unit u = player.unit();
        if (u == null) return false;
        if (u.activelyBuilding()) return true;
        return control != null && control.input != null
                && control.input.isBuilding
                && u.plans.size > 0;
    }
}
