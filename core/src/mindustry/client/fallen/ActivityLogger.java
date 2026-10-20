package mindustry.client.fallen;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.client.ClientVars;
import mindustry.client.fallen.ActionsHistory.*;
import mindustry.content.Blocks;
import mindustry.core.NetClient;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.UnitType;
import mindustry.ui.Fonts;
import mindustry.ui.fragments.ChatFragment;
import mindustry.world.blocks.storage.CoreBlock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mindustry.Vars.*;

public class ActivityLogger {

    private static boolean initialized = false;
    /** Same player and role keep one chat line while losses keep arriving. */
    private static final long deathAlarmWindow = 20_000L;
    private static final ObjectMap<String, DeathAlarm> deathAlarms = new ObjectMap<>();

    public static void init() {
        // PanelFragment.startInit() and UI both call this; register handlers only once
        if(initialized) return;
        initialized = true;

        Timer.schedule(() -> {
            if (Vars.ui.chatfrag == null || !Vars.state.isGame()) return;

            StringBuilder stats = new StringBuilder();
            stats.append("[accent]History Status: [white]")
                    .append("Blocks: ").append(ActionsHistory.blocksplayersplans.size).append(" | ")
                    .append("Configs: ").append(ActionsHistory.blockconfplayersplans.size).append(" | ")
                    .append("Items: ").append(ActionsHistory.playeritemsplans.size).append(" | ")
                    .append("Deaths: ").append(ActionsHistory.deathunitsplan.size).append(" | ")
                    .append("UnitsCmd: ").append(ActionsHistory.unitcommandsplans.size).append(" | ")
                    .append("UnitsState: ").append(ActionsHistory.unitstatesplans.size);

            String finalMsg = stats.toString();

            //Vars.ui.chatfrag.addMessage(finalMsg, null, null, "", finalMsg);

        }, 100, 100);

        HistoryRenderer.init();

        Timer.schedule(() -> {
            if(Vars.state.isMenu() || !Vars.net.active()) return;
            checkGriefersFromHistory();
        }, 0, 5);

        Events.on(BlockDestroyEvent.class, event -> {
            if(!Core.settings.getBool("coredeathalarm")) return;
            if(state.rules.coreCapture && Core.settings.getBool("coredeathalarmrecap")) return;

            if(event.tile.build instanceof CoreBlock.CoreBuild core){
                int cx = Mathf.ceil(core.x / 8f);
                int cy = Mathf.ceil(core.y / 8f);
                String msg;

                if(core.team == player.team()){
                    msg = Core.bundle.format("client.coredeath.ours", cx, cy);

                    if(Core.settings.getBool("unitatchat") && !state.rules.coreCapture){
                        if(state.rules.pvp) {
                            Call.sendChatMessage("/t " + msg);
                        } else {
                            Call.sendChatMessage(msg);
                        }
                    } else if (!state.rules.coreCapture){
                        addLocalMessage(msg);
                    }
                } else {
                    msg = Core.bundle.format("client.coredeath.team", core.team.color, core.team.name, cx, cy);
                    addLocalMessage(msg);
                }
            }
        });

        Events.on(BlockBuildBeginEventBefore.class, it -> {
            if(!Core.settings.getBool("blocksplayersplan", true)) return;
            if (it.unit == null) return;

            String name = it.unit.getControllerName();
            String rawName = Strings.stripColors(name == null ? "Unknown" : name);

            if (it.newBlock == null || it.newBlock == Blocks.air) {
                if (it.tile.build == null || !it.tile.block().rebuildable) return;

                ActionsHistory.blocksplayersplans.addFirst(new BlockPlayerPlan(
                        it.tile.x, it.tile.y, (short) it.tile.build.rotation,
                        it.tile.build.block.id, it.tile.build.config(),
                        Strings.stripColors(rawName), it.breaking
                ));
            } else {
                if (it.tile == null) return;

                ActionsHistory.blocksplayersplans.addFirst(new BlockPlayerPlan(
                        it.tile.x, it.tile.y, (short) 0,
                        it.newBlock.id, null,
                        Strings.stripColors(rawName), it.breaking
                ));
            }
        });

        Events.on(BuildRotateEvent.class, it -> {
            if(!Core.settings.getBool("blocksplayersplan", true)) return;
            if (it.unit == null) return;
            if (it.build == null || it.unit.controller() == null) return;
            String rawPlayerName = Strings.stripColors(it.unit.getControllerName() == null ? "Unknown" : it.unit.getControllerName());

            ActionsHistory.blockconfplayersplans.addFirst(new BlockConfigPlayerPlan(
                    (int)it.build.x/8, (int)it.build.y/8, it.build.block.id, rawPlayerName)
            );
        });

        Events.on(ConfigEvent.class, it -> {
            if(!Core.settings.getBool("blocksplayersplan", true)) return;
            if (it.tile.block == null || it.player == null) return;
            String rawPlayerName = Strings.stripColors(it.player == null ? "Unknown" : it.player.name);


            ActionsHistory.blockconfplayersplans.addFirst(new BlockConfigPlayerPlan(
                    (int)it.tile.x/8, (int)it.tile.y/8, it.tile.block.id, rawPlayerName
            ));
        });

        Events.on(DepositEvent.class, it -> {
            if(!Core.settings.getBool("itemslog", true)) return;
            if (it.tile.block == null || it.player == null) return;

            ActionsHistory.playeritemsplans.addFirst(new ItemPlayerPlan(
                    it.player, it.tile.tile, it.item, false
            ));
        });

        Events.on(WithdrawEvent.class, it -> {
            if(!Core.settings.getBool("itemslog", true)) return;
            if (it.tile.block == null || it.player == null) return;

            ActionsHistory.playeritemsplans.addFirst(new ItemPlayerPlan(
                    it.player, it.tile.tile, it.item, true
            ));
        });

        Events.on(ResetEvent.class, e -> deathAlarms.clear());

        Events.on(UnitDeadEvent.class, it -> {
            if (it.unit == null || it.unit.type == null) return;

            Player p = it.unit.getPlayer();
            String commander = it.unit.lastCommanded;
            if (p == null && commander == null) return;

            boolean canAlarm = Core.settings.getBool("playerunitdeathalarm");
            int hpLimit = Core.settings.getInt("playerunitdeathalarmhp", 500);
            boolean isHeavyUnit = it.unit.maxHealth >= hpLimit && hpLimit > 0;

            if (p != null) {
                ActionsHistory.deathunitsplan.addFirst(new ActionsHistory.UnitsKilledByPlayers(
                        p, it.unit.type, it.unit.x, it.unit.y
                ));

                if (canAlarm && isHeavyUnit) {
                    alarmUnitDeath(true, p.name, it.unit.type, String.valueOf(it.unit.team.color),
                            (int)Mathf.ceil(it.unit.lastX / 8f), (int)Mathf.ceil(it.unit.lastY / 8f));
                }
            } else {
                ActionsHistory.deathunitscontrolplan.addFirst(new ActionsHistory.UnitsKilledByControllPlayers(
                        commander, it.unit.type, it.unit.x, it.unit.y
                ));

                if (canAlarm && isHeavyUnit) {
                    alarmUnitDeath(false, commander, it.unit.type, String.valueOf(it.unit.team.color),
                            (int)Mathf.ceil(it.unit.lastX / 8f), (int)Mathf.ceil(it.unit.lastY / 8f));
                }
            }
        });
        Events.on(UnitCommandPositionEvent.class, it -> {
            if(!Core.settings.getBool("unitlog", true)) return;

            Seq<UnitTypeCount> grouped = groupUnits(it.unitIds);
            if (grouped.isEmpty()) return;

            String targetName = "None";
            if (it.target instanceof Building b) {
                targetName = b.block.localizedName;
            } else if (it.target instanceof Unit u) {
                targetName = u.type.localizedName;
            } else if (it.pos != null) {
                targetName = "[" + (int)(it.pos.x / 8) + ", " + (int)(it.pos.y / 8) + "]";
            }

            ActionsHistory.unitcommandsplans.addFirst(new UnitCommandHistoryPlan(
                    Strings.stripColors(it.player.name), grouped,
                    it.pos != null ? it.pos.x : (it.target != null ? it.target.getX() : 0),
                    it.pos != null ? it.pos.y : (it.target != null ? it.target.getY() : 0),
                    targetName
            ));

            if(Core.settings.getBool("unitcontrolalarm", false) && it.unitIds.length > Core.settings.getInt("unitcontrolalarmcount", 100)) {
                if(it.player == Vars.player && !Core.settings.getBool("unitcontrolselfalarm", false)) return;

                StringBuilder unitIconsStr = new StringBuilder();
                for (var ut : grouped) {
                    unitIconsStr.append(Fonts.getUnicodeStr(ut.type.name)).append(ut.count).append(" ");
                }

                addLocalMessage("[orange]⚠ [white]" + it.player.name + " [gray]командует: [white]" + unitIconsStr.toString() + "[gray]-> [sky]" + targetName);
            }
        });

        Events.on(UnitStateChangeEvent.class, it -> {
            if(!Core.settings.getBool("unitlog", true)) return;

            Seq<UnitTypeCount> grouped = groupUnits(it.unitIds);
            if (grouped.isEmpty()) return;

            ActionsHistory.unitstatesplans.addFirst(new UnitStateHistoryPlan(
                    Strings.stripColors(it.player.name), grouped,
                    it.command.name != null ? it.command.name : it.command.name
            ));

            if(Core.settings.getBool("unitcontrolalarm", false) && it.unitIds.length > Core.settings.getInt("unitcontrolalarmcount", 100)) {
                if(it.player == Vars.player && !Core.settings.getBool("unitcontrolselfalarm", false)) return;

                StringBuilder unitIconsStr = new StringBuilder();
                for (var ut : grouped) {
                    unitIconsStr.append(Fonts.getUnicodeStr(ut.type.name)).append(ut.count).append(" ");
                }

                addLocalMessage("[orange]⚠ [white]" + it.player.name + " [gray]командует: [white]" + unitIconsStr.toString() + "[gray]-> [sky]" + it.command.name);
            }
        });
    }

    // Вспомогательный метод для группировки юнитов по типам
    private static Seq<UnitTypeCount> groupUnits(int[] ids) {
        ObjectIntMap<UnitType> counts = new ObjectIntMap<>();
        for (int id : ids) {
            Unit unit = Groups.unit.getByID(id);
            if (unit != null) {
                counts.put(unit.type, counts.get(unit.type, 0) + 1);
            }
        }
        Seq<UnitTypeCount> result = new Seq<>();
        counts.forEach(entry -> result.add(new UnitTypeCount(entry.key, entry.value)));
        return result;
    }
    private static ChatFragment.ChatMessage addLocalMessage(String msg) {
        if (ui.chatfrag == null) return null;
        ChatFragment.ChatMessage m = ui.chatfrag.addMessage(msg, null, null, "", msg);
        NetClient.findCoords(m);
        return m;
    }

    /** One chat line per player: later deaths of the same role update the count. */
    private static void alarmUnitDeath(boolean piloted, String who, UnitType type, String color, int x, int y) {
        if (ui.chatfrag == null || type == null) return;
        String name = who == null ? "" : who;
        String key = (piloted ? "p:" : "c:") + Strings.stripColors(name);
        DeathAlarm alarm = deathAlarms.get(key);
        int idx = alarm == null || alarm.msg == null || ui.chatfrag.messages == null ? -1 : ui.chatfrag.messages.indexOf(alarm.msg);
        boolean stack = alarm != null && idx >= 0 && Time.timeSinceMillis(alarm.last) < deathAlarmWindow;
        if (!stack) {
            alarm = new DeathAlarm(piloted, name);
            deathAlarms.put(key, alarm);
        }
        alarm.add(type, color);
        alarm.x = x;
        alarm.y = y;
        alarm.last = Time.millis();
        String text = deathText(alarm);
        if (!stack) {
            alarm.msg = addLocalMessage(text);
        } else if (alarm.msg != null) {
            ui.chatfrag.doFade(2f);
            alarm.msg.message = text;
            alarm.msg.unformatted = text;
            alarm.msg.format();
            if (alarm.msg.buttons != null) alarm.msg.buttons.clear();
            NetClient.findCoords(alarm.msg);
        }
    }

    private static String deathText(DeathAlarm alarm) {
        int total = alarm.total();
        if (total <= 1) {
            UnitLoss only = alarm.losses.first();
            if (alarm.piloted) {
                return "!![#fa]⚠[]:  [#" + only.color + "]" + only.type.localizedName + " убито с позором. [" + alarm.x + "," + alarm.y + "] Пилот: " + alarm.who;
            }
            return "!![#fa]⚠[]: [#" + only.color + "]" + only.type.localizedName + " бездарно потерян. [" + alarm.x + "," + alarm.y + "] Командир: " + alarm.who;
        }

        Seq<UnitLoss> ordered = new Seq<>(alarm.losses);
        ordered.sort((a, b) -> Integer.compare(b.count, a.count));
        StringBuilder units = new StringBuilder();
        for (int i = 0; i < ordered.size; i++) {
            UnitLoss loss = ordered.get(i);
            if (i > 0) units.append(", ");
            units.append("[white]").append(loss.count).append("[] [#").append(loss.color).append("]").append(loss.type.localizedName).append("[]");
        }
        String verb = alarm.piloted ? "убито с позором" : "бездарно потеряно";
        String role = alarm.piloted ? "Пилот: " : "Командир: ";
        return "!![#fa]⚠[]: " + units + " " + verb + ". [" + alarm.x + "," + alarm.y + "] " + role + alarm.who;
    }

    private static final class DeathAlarm {
        final boolean piloted;
        final String who;
        final Seq<UnitLoss> losses = new Seq<>();
        int x, y;
        ChatFragment.ChatMessage msg;
        long last;

        DeathAlarm(boolean piloted, String who) {
            this.piloted = piloted;
            this.who = who;
        }

        void add(UnitType type, String color) {
            UnitLoss loss = losses.find(l -> l.type == type);
            if (loss == null) {
                losses.add(new UnitLoss(type, color));
            } else {
                loss.count++;
                loss.color = color;
            }
        }

        int total() {
            int n = 0;
            for (int i = 0; i < losses.size; i++) n += losses.get(i).count;
            return n;
        }
    }

    private static final class UnitLoss {
        final UnitType type;
        int count = 1;
        String color;

        UnitLoss(UnitType type, String color) {
            this.type = type;
            this.color = color;
        }
    }

    private static void checkGriefersFromHistory() {
        if (!Core.settings.getBool("alarmgriefblocks", false)) return;

        int minB = Core.settings.getInt("alarmgriefblocksbuild", 10);
        int maxBr = Core.settings.getInt("alarmgriefblocksbreake", 100);

        ObjectMap<String, int[]> stats = new ObjectMap<>();

        for (ActionsHistory.BlockPlayerPlan plan : ActionsHistory.blocksplayersplans) {
            if (plan.lastacs == null || plan.lastacs.isEmpty()) continue;

            int[] counts = stats.get(plan.lastacs, () -> new int[2]);

            if (plan.wasbreaking) {
                counts[1]++;
            } else {
                counts[0]++;
            }
        }

        for (var entry : stats.entries()) {
            String name = entry.key;
            int builds = entry.value[0];
            int breaks = entry.value[1];

            if (ActionsHistory.warnedGriefers.contains(name)) continue;

            boolean isSuspicious = (breaks >= maxBr) && (builds <= minB);

            if (isSuspicious) {
                ActionsHistory.warnedGriefers.add(name);

                String alert = "[scarlet]Тревога: похоже на гриферство[]\n" +
                        "[accent]Игрок:[] " + name + "\n" +
                        "[lightgray]Сломано:[] " + breaks + " | [lightgray]Построено:[] " + builds;

                Vars.player.sendMessage(alert);
            }
        }
    }
}