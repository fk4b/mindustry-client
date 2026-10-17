package mindustry.client.morj;

import arc.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.client.morj.mk.*;
import mindustry.client.morj.mk.fs.*;
import mindustry.client.morj.mk.rules.*;
import mindustry.client.morj.mk.ui.*;
import mindustry.editor.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

/**
 * Map-editor tools from MindurkaCompat, built into the client.
 * If that mod is enabled it still owns the editor, so the two copies do not both replace it.
 */
public final class MindurkaEditor{
    private static boolean installed;

    private MindurkaEditor(){}

    public static void install(){
        if(installed) return;
        Mods.LoadedMod mod = mods.getMod("mindurkacompat");
        if(mod != null && mod.enabled()) return;
        installed = true;

        Events.on(WorldLoadEndEvent.class, event -> {
            MVars.rules = new MRules(state.rules, world.width(), world.height());
            if(MVars.rules.gamemode() != null &&
                Core.settings.getBool("mindurka.enableeditor", true) &&
                (!net.active() || net.server()) &&
                (MVars.mapEditor == null || !MVars.mapEditor.isLoading())
            ){
                try{
                    MVars.rules.gamemode().dataFixer();
                    MVars.rules.gamemode().onStart();
                }catch(Exception e){
                    Log.err("Mindurka gamemode failed to start", e);
                }
            }
        });

        ui.settings.addCategory("@settings.mindurka", Icon.editor, table -> {
            table.checkPref("mindurka.enableeditor", true);
            table.sliderPref("mindurka.zoomsensitivity", 100, 1, 200, x -> x + "%");
            table.sliderPref("mindurka.guideslinewidth", 1, 1, 20, x -> x + "px");
            table.checkPref("mindurka.guidesoutline", false);
        });

        MIcons.load();
        Gamemode.init();
        FortsPlotKind.init();
        FileSystem.init();
        MVars.atlas = new OTextureAtlas(Core.atlas);

        if(!Core.settings.getBool("mindurka.enableeditor", true)) return;

        MapEditorDialog previous = ui.editor;
        MVars.oldMapView = previous.getView();
        OMapEditor.inject();
        MVars.mapEditor = (OMapEditor)editor;
        ui.editor = MVars.editorDialog = new OEditorDialog(MVars.mapEditor, previous);
        OCustomRulesDialog.inject();
    }
}
