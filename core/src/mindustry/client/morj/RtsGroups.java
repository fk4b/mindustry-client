package mindustry.client.morj;

import arc.*;
import arc.input.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.ui.*;

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
            if(unit != null && unit.isValid() && !unit.isPlayer() && unit.isCommandable() && match(unit, kind)){
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
        Core.app.post(() -> Events.fire(Trigger.unitCommandChange));
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
