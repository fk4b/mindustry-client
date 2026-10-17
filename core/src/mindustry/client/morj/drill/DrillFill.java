package mindustry.client.morj.drill;

import arc.*;
import arc.math.geom.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.client.morj.drill.DrillLayout.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;

import static mindustry.Vars.*;

/** Click an ore while this is on. A drill button queues a layout on the player unit. */
public final class DrillFill{
    private static boolean on;
    private static boolean hooked;
    private static Tile selected;
    private static final Table picks = new Table(Styles.black3);
    private static final Table dirs = new Table(Styles.black3);
    private static Block pending;

    private DrillFill(){}

    public static boolean enabled(){
        return on;
    }

    public static void init(){
        if(hooked) return;
        hooked = true;
        picks.visible = false;
        dirs.visible = false;
        ui.hudGroup.addChild(picks);
        ui.hudGroup.addChild(dirs);
        Events.on(TapEvent.class, DrillFill::tapped);

        dirs.button(Icon.up, Styles.clearNonei, () -> aim(Direction.up)).size(32f);
        dirs.row();
        dirs.table(row -> {
            row.button(Icon.left, Styles.clearNonei, () -> aim(Direction.left)).size(32f);
            row.button(Icon.cancel, Styles.clearNonei, () -> dirs.visible = false).size(32f);
            row.button(Icon.right, Styles.clearNonei, () -> aim(Direction.right)).size(32f);
        });
        dirs.row();
        dirs.button(Icon.down, Styles.clearNonei, () -> aim(Direction.down)).size(32f);
    }

    public static void toggle(){
        init();
        on = !on;
        picks.visible = false;
        dirs.visible = false;
        ui.showInfoFade(Core.bundle.get(on ? "client.drill.on" : "client.drill.off"));
    }

    public static void showSettings(){
        BaseDialog dialog = new BaseDialog("@client.drill.settings");
        dialog.cont.defaults().left();
        Label tiles = new Label("");
        Slider slider = new Slider(25f, 400f, 25f, false);
        slider.setValue(Core.settings.getInt("autodrill-maxtiles", 200));
        tiles.setText(Integer.toString((int)slider.getValue()));
        slider.changed(() -> {
            int next = (int)slider.getValue();
            Core.settings.put("autodrill-maxtiles", next);
            tiles.setText(Integer.toString(next));
        });
        dialog.cont.add("@client.drill.maxtiles").padBottom(4f).row();
        dialog.cont.table(row -> {
            row.add(slider).width(280f);
            row.add(tiles).width(48f).padLeft(8f);
        }).row();
        CheckBox support = new CheckBox("@client.drill.support");
        support.setChecked(Core.settings.getBool("autodrill-support", true));
        support.changed(() -> Core.settings.put("autodrill-support", support.isChecked()));
        dialog.cont.add(support).padTop(10f).row();
        dialog.addCloseButton();
        dialog.show();
    }

    private static void tapped(TapEvent event){
        if(!on || state.isMenu() || event.tile == null) return;
        if(Core.scene.hasDialog() || picks.hasMouse() || dirs.hasMouse()) return;
        selected = event.tile;
        dirs.visible = false;
        rebuild();
    }

    private static void rebuild(){
        picks.clearChildren();
        if(selected == null){
            picks.visible = false;
            return;
        }
        for(Block block : content.blocks()){
            if(block.isHidden() || !block.unlockedNow() || !block.environmentBuildable()) continue;
            boolean mine = block instanceof Drill drill && drill.canMine(selected);
            boolean wall = block instanceof BeamDrill beam && selected.wallDrop() != null && selected.wallDrop().hardness <= beam.tier;
            if(!mine && !wall) continue;
            Block chosen = block;
            picks.button(new TextureRegionDrawable(block.uiIcon), Styles.clearNonei, () -> choose(chosen)).size(36f).tooltip(block.localizedName);
        }
        if(!picks.hasChildren()){
            picks.visible = false;
            ui.showInfoFade("@client.drill.empty");
            return;
        }
        place(picks);
        picks.visible = true;
    }

    private static void choose(Block block){
        pending = block;
        picks.visible = false;
        if(block instanceof Drill drill && drill.size != 2){
            run(null);
        }else if(block instanceof BeamDrill || block instanceof Drill){
            place(dirs);
            dirs.visible = true;
        }
    }

    private static void aim(Direction direction){
        dirs.visible = false;
        run(direction);
    }

    private static void run(Direction direction){
        if(selected == null || pending == null) return;
        int n = DrillLayout.fill(selected, pending, direction);
        if(n < 0) ui.showInfoFade("@client.drill.nounit");
        else if(n == 0) ui.showInfoFade("@client.drill.none");
        else ui.showInfoFade(Core.bundle.format("client.drill.placed", n));
        on = false;
    }

    private static void place(Table table){
        if(selected == null) return;
        Vec2 v = Core.camera.project(selected.worldx(), selected.worldy() + tilesize);
        table.pack();
        table.setPosition(v.x, v.y, Align.bottom);
        table.toFront();
    }
}
