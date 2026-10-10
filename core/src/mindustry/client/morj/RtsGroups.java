package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.input.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.input.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.units.*;

import static mindustry.Vars.*;

/** Command-mode filters. Changes the local selection only. */
public final class RtsGroups{
    public enum Kind{
        high, low, offense, support, flying, ground, naval
    }

    private RtsGroups(){}

    public static boolean enabled(){
        return Core.settings.getBool("rtsgroups", true);
    }

    /** Factory select, the classic-bar switch, the color hint, then the filters when they are on. */
    public static void toolbar(Table table){
        table.table(row -> {
            row.left();
            TextButton factories = new TextButton(Core.bundle.get("client.morj.rts.factories"), Styles.flatBordert);
            factories.clicked(KeyCode.mouseLeft, RtsGroups::selectFactories);
            factories.clicked(KeyCode.mouseRight, RtsGroups::clearFactories);
            row.add(factories).height(28f).pad(1f).tooltip(Core.bundle.get("client.morj.rts.factories.tip"));

            boolean classic = !enabled();
            TextButton view = new TextButton(Core.bundle.get(classic ? "client.morj.rts.filters" : "client.morj.rts.classic"), Styles.flatBordert);
            view.clicked(() -> {
                Core.settings.put("rtsgroups", classic);
                changed();
            });
            row.add(view).height(28f).pad(1f).tooltip(Core.bundle.get(classic ? "client.morj.rts.filters.tip" : "client.morj.rts.classic.tip"));
        }).left().padBottom(2f).row();

        if(enabled()) buttons(table);

        Label hint = table.add(Core.bundle.get("client.morj.rts.colors")).left().growX().get();
        hint.setWrap(true);
        hint.setFontScale(0.72f);
        hint.setColor(Color.lightGray);
        table.row();
    }

    public static void buttons(Table table){
        table.table(row -> {
            row.left();
            btn(row, "client.morj.rts.high", Kind.high);
            btn(row, "client.morj.rts.low", Kind.low);
            btn(row, "client.morj.rts.offense", Kind.offense);
            btn(row, "client.morj.rts.support", Kind.support);
            btn(row, "client.morj.rts.flying", Kind.flying);
            btn(row, "client.morj.rts.ground", Kind.ground);
            btn(row, "client.morj.rts.naval", Kind.naval);
        }).left().padBottom(4f).row();
    }

    /** Own unit factories, reconstructors and assemblers. Units are dropped from the selection. */
    public static void selectFactories(){
        if(player == null || player.team() == null || player.team().data() == null) return;
        Seq<Building> selected = control.input.commandBuildings;
        selected.clear();
        control.input.selectedUnits.clear();
        for(Building build : player.team().data().buildings){
            if(build != null && build.isValid() && build.team == player.team() && isProduction(build)){
                selected.add(build);
            }
        }
        ui.showInfoToast(Core.bundle.format("client.morj.rts.factories.selected", selected.size), 1.1f);
        changed();
    }

    public static void clearFactories(){
        int size = control.input.commandBuildings.size;
        control.input.commandBuildings.clear();
        ui.showInfoToast(Core.bundle.format("client.morj.rts.removed", size), 1.1f);
        changed();
    }

    public static void fillBuildings(Table table){
        Table list = new Table();
        list.left();
        ObjectMap<Block, Integer> counts = new ObjectMap<>();
        for(Building build : control.input.commandBuildings){
            if(build == null || !build.isValid() || build.block == null) continue;
            counts.put(build.block, counts.get(build.block, 0) + 1);
        }
        for(ObjectMap.Entry<Block, Integer> entry : counts){
            list.add(new Image(entry.key.uiIcon)).size(28f).pad(2f).tooltip(entry.key.localizedName);
            list.add(Integer.toString(entry.value)).padRight(8f);
        }
        table.add(list).left().growX().row();
        table.add(Core.bundle.get("client.morj.rts.rally")).color(Color.lightGray).left().pad(4f).growX();
    }

    private static boolean isProduction(Building build){
        Block block = build.block;
        return block instanceof UnitFactory || block instanceof Reconstructor || block instanceof UnitAssembler;
    }

    private static void changed(){
        Core.app.post(() -> Events.fire(Trigger.unitCommandChange));
    }

    private static void btn(Table table, String key, Kind kind){
        TextButton button = new TextButton(Core.bundle.get(key), Styles.flatBordert);
        button.clicked(KeyCode.mouseLeft, () -> apply(kind, false));
        button.clicked(KeyCode.mouseRight, () -> apply(kind, true));
        table.add(button).size(50f, 28f).pad(1f);
    }

    public static void apply(Kind kind, boolean removeOnly){
        if(player == null || player.team() == null || player.team().data() == null) return;
        Seq<Unit> selected = control.input.selectedUnits;
        Seq<Unit> hits = new Seq<>();
        for(Unit unit : player.team().data().units){
            if(unit != null && unit.isValid() && !unit.isPlayer() && unit.isCommandable() && !InputHandler.commandHides(unit) && match(unit, kind)){
                hits.add(unit);
            }
        }
        if(removeOnly){
            int before = selected.size;
            selected.removeAll(unit -> match(unit, kind));
            ui.showInfoToast(Core.bundle.format("client.morj.rts.removed", before - selected.size), 1.1f);
        }else if(hits.isEmpty()){
            ui.showInfoToast(Core.bundle.format("client.morj.rts.selected", 0), 1.1f);
            return;
        }else{
            // Left click always selects. Toggling off emptied the bar, and that bar then rebuilt every frame.
            control.input.commandBuildings.clear();
            selected.clear();
            selected.addAll(hits);
            ui.showInfoToast(Core.bundle.format("client.morj.rts.selected", hits.size), 1.1f);
        }
        // After the click, so the button is not destroyed inside its own touchUp.
        changed();
    }

    static boolean match(Unit unit, Kind kind){
        float wound = Core.settings.getInt("rtswound", 50) / 100f;
        if(wound <= 0f) wound = 0.5f;
        return switch(kind){
            case flying -> unit.type.flying;
            case naval -> unit.type.naval;
            case ground -> !unit.type.flying && !unit.type.naval;
            case support -> unit.type.buildSpeed > 0f || unit.type.canHeal;
            case offense -> unit.type.weapons.size > 0 && unit.type.buildSpeed <= 0f && !unit.type.canHeal;
            case high -> unit.healthf() >= wound;
            case low -> unit.healthf() < wound;
        };
    }
}
