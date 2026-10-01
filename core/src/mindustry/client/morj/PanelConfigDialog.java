package mindustry.client.morj;

import arc.*;
import arc.input.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.client.ui.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/** Show, hide, resize and drag each morj panel icon into a cell. */
public class PanelConfigDialog extends BaseDialog{
    private static PanelConfigDialog instance;
    private final Table tabs = new Table();
    private final Table list = new Table();
    private int editTab;
    private Table ghost;
    private Drawable cellBg, emptyBg;

    public static void open(){
        if(ui == null || ui.panelfragment == null) return;
        ui.panelfragment.registerPages();
        if(instance == null) instance = new PanelConfigDialog();
        instance.editTab = ui.panelfragment.currentTab();
        instance.show();
    }

    private PanelConfigDialog(){
        super("@client.morj.panel.title");
        addCloseButton();
        cont.add("@client.morj.panel.hint").growX().wrap().pad(6f).row();
        cont.add(tabs).growX().padBottom(4f).row();
        ScrollPane pane = cont.pane(list).grow().maxHeight(Core.graphics.getHeight() * 0.62f).get();
        pane.setFlickScroll(false);
        pane.setCancelTouchFocus(false);
        buttons.button("@client.morj.panel.reset", () -> {
            Core.settings.remove("morj-panel-hide");
            Core.settings.remove("morj-panel-order");
            Core.settings.remove("morj-panel-size");
            Core.settings.remove("morj-panel-pos");
            Core.settings.remove("morj-panel-cols");
            PanelLayout.reload();
            ui.panelfragment.rebuild();
            Core.app.post(this::rebuild);
        });
        hidden(() -> {
            if(ghost != null){
                ghost.remove();
                ghost = null;
            }
        });
        shown(this::rebuild);
    }

    private void rebuild(){
        if(cellBg == null){
            cellBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.14f);
            emptyBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.05f);
        }
        tabs.clear();
        list.clear();
        list.top().left();
        if(ui == null || ui.panelfragment == null) return;
        String[] keys = {"client.morj.tab.mine", "client.morj.tab.build", "client.morj.tab.view", "client.morj.tab.fight", "client.morj.tab.server"};
        for(int i = 0; i < keys.length; i++){
            int tab = i;
            tabs.button(Core.bundle.get(keys[i]), Styles.flatt, () -> {
                editTab = tab;
                Core.app.post(this::rebuild);
            }).checked(b -> editTab == tab).height(32f).growX().pad(1f);
        }
        int width = PanelLayout.cols(editTab);
        tabs.add("@client.morj.panel.cols").padLeft(10f);
        tabs.button("-", Styles.cleart, () -> changeCols(-1)).size(32f).disabled(width <= 1);
        tabs.add(String.valueOf(width)).width(28f).center();
        tabs.button("+", Styles.cleart, () -> changeCols(1)).size(32f).disabled(width >= 12);

        Seq<PanelFragment.PanelIcon> icons = ui.panelfragment.tabIcons(editTab);
        Seq<String> visible = new Seq<>();
        Seq<PanelFragment.PanelIcon> hiddenIcons = new Seq<>();
        ObjectMap<String, PanelFragment.PanelIcon> byId = new ObjectMap<>();
        for(PanelFragment.PanelIcon icon : icons){
            byId.put(icon.id, icon);
            if(PanelLayout.hidden(icon.id)) hiddenIcons.add(icon);
            else visible.add(icon.id);
        }
        Seq<PanelLayout.Slot> slots = PanelLayout.resolve(visible, width);
        ObjectMap<String, PanelLayout.Slot> at = new ObjectMap<>();
        int maxC = width - 1, maxR = 0;
        for(PanelLayout.Slot slot : slots){
            at.put(slot.col + ":" + slot.row, slot);
            maxC = Math.max(maxC, slot.col);
            maxR = Math.max(maxR, slot.row);
        }
        int showC = Math.min(17, Math.max(width, maxC + 1) + 1);
        int showR = Math.min(25, Math.max(1, maxR + 1) + 1);
        Table board = new Table();
        board.top().left();
        for(int r = 0; r < showR; r++){
            for(int c = 0; c < showC; c++){
                PanelLayout.Slot slot = at.get(c + ":" + r);
                PanelFragment.PanelIcon icon = slot == null ? null : byId.get(slot.id);
                board.add(icon == null ? emptyCell(c, r) : iconCell(icon, c, r)).size(156f, 74f).pad(2f);
            }
            board.row();
        }
        list.add(board).left().row();
        if(hiddenIcons.size > 0){
            list.add("@client.morj.panel.hidden").left().padTop(8f).padBottom(4f).row();
            for(PanelFragment.PanelIcon icon : hiddenIcons){
                list.add(hiddenRow(icon)).growX().padBottom(2f).row();
            }
        }
    }

    private Table emptyCell(int col, int row){
        Table cell = new Table();
        cell.background(emptyBg);
        cell.userObject = col + ":" + row;
        return cell;
    }

    private Table iconCell(PanelFragment.PanelIcon icon, int col, int row){
        Table cell = new Table();
        cell.background(cellBg);
        cell.userObject = col + ":" + row;
        Table handle = new Table();
        handle.image(Icon.move).size(16f).padRight(3f);
        Label name = handle.add(icon.caption).growX().left().get();
        name.setFontScale(0.72f);
        name.setEllipsis(true);
        name.setAlignment(Align.left);
        drag(handle, icon, col, row);
        cell.add(handle).growX().left().colspan(4).pad(3f, 4f, 1f, 4f)
            .tooltip(icon.label + "\n[lightgray]" + Core.bundle.get("client.morj.panel.dragbtn")).row();
        CheckBox box = new CheckBox("");
        box.setChecked(true);
        box.changed(() -> {
            PanelLayout.setHidden(icon.id, !box.isChecked());
            ui.panelfragment.rebuild();
            Core.app.post(this::rebuild);
        });
        cell.add(box).left().size(28f).padLeft(2f);
        cell.button("-", Styles.cleart, () -> scale(icon.id, -0.2f)).size(26f);
        cell.add(Strings.fixed(PanelLayout.scale(icon.id), 1)).width(32f).center();
        cell.button("+", Styles.cleart, () -> scale(icon.id, 0.2f)).size(26f).padRight(2f);
        return cell;
    }

    private Table hiddenRow(PanelFragment.PanelIcon icon){
        Table row = new Table();
        CheckBox box = new CheckBox(icon.caption);
        box.setChecked(false);
        box.changed(() -> {
            PanelLayout.setHidden(icon.id, !box.isChecked());
            ui.panelfragment.rebuild();
            Core.app.post(this::rebuild);
        });
        row.add(box).growX().left().padRight(6f);
        row.button("-", Styles.cleart, () -> scale(icon.id, -0.2f)).size(32f);
        row.add(Strings.fixed(PanelLayout.scale(icon.id), 1)).width(36f).center();
        row.button("+", Styles.cleart, () -> scale(icon.id, 0.2f)).size(32f);
        return row;
    }

    private void scale(String id, float delta){
        PanelLayout.setScale(id, PanelLayout.scale(id) + delta);
        ui.panelfragment.rebuild();
        Core.app.post(this::rebuild);
    }

    private void changeCols(int dir){
        int next = Mathf.clamp(PanelLayout.cols(editTab) + dir, 1, 12);
        Seq<PanelLayout.Slot> slots = PanelLayout.resolve(visibleIds(), PanelLayout.cols(editTab));
        slots.sort(Structs.comparingInt(s -> s.row * 32 + s.col));
        Seq<String> ids = new Seq<>();
        for(PanelLayout.Slot slot : slots) ids.add(slot.id);
        Seq<String> hiddenIds = new Seq<>();
        for(PanelFragment.PanelIcon icon : ui.panelfragment.tabIcons(editTab)){
            if(PanelLayout.hidden(icon.id)) hiddenIds.add(icon.id);
        }
        PanelLayout.reflow(ids, hiddenIds, editTab, next);
        ui.panelfragment.rebuild();
        Core.app.post(this::rebuild);
    }

    private Seq<String> visibleIds(){
        Seq<String> ids = new Seq<>();
        for(PanelFragment.PanelIcon icon : ui.panelfragment.tabIcons(editTab)){
            if(!PanelLayout.hidden(icon.id)) ids.add(icon.id);
        }
        return ids;
    }

    private void drag(Table handle, PanelFragment.PanelIcon icon, int col, int row){
        float[] start = new float[2];
        boolean[] moved = {false};
        handle.addListener(new InputListener(){
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(button != KeyCode.mouseLeft) return false;
                start[0] = event.stageX;
                start[1] = event.stageY;
                moved[0] = false;
                return true;
            }
            @Override public void touchDragged(InputEvent event, float x, float y, int pointer){
                if(!moved[0] && Math.abs(event.stageX - start[0]) + Math.abs(event.stageY - start[1]) < 8f) return;
                moved[0] = true;
                if(ghost == null){
                    ghost = new Table(Tex.pane);
                    ghost.touchable = Touchable.disabled;
                    ghost.margin(6f);
                    ghost.add(icon.caption);
                    ghost.pack();
                    Core.scene.root.addChild(ghost);
                }
                ghost.setPosition(event.stageX + 10f, event.stageY - ghost.getHeight() - 8f);
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(ghost != null){
                    ghost.remove();
                    ghost = null;
                }
                if(!moved[0]) return;
                String target = cellAt(event.stageX, event.stageY);
                if(target == null) return;
                int colon = target.indexOf(':');
                if(colon <= 0) return;
                int toCol = Strings.parseInt(target.substring(0, colon), -1);
                int toRow = Strings.parseInt(target.substring(colon + 1), -1);
                if(toCol < 0 || toRow < 0 || (toCol == col && toRow == row)) return;
                PanelLayout.relocate(visibleIds(), PanelLayout.cols(editTab), icon.id, toCol, toRow);
                ui.panelfragment.rebuild();
                Core.app.post(PanelConfigDialog.this::rebuild);
            }
        });
    }

    private String cellAt(float stageX, float stageY){
        // The grid is the first child of the scrolled list.
        if(list.getChildren().size == 0) return null;
        Element board = list.getChildren().first();
        if(!(board instanceof Group grid)) return null;
        for(Element child : grid.getChildren()){
            if(!(child.userObject instanceof String key)) continue;
            child.stageToLocalCoordinates(Tmp.v1.set(stageX, stageY));
            if(Tmp.v1.x >= 0f && Tmp.v1.y >= 0f && Tmp.v1.x <= child.getWidth() && Tmp.v1.y <= child.getHeight()) return key;
        }
        return null;
    }
}
