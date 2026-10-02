package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
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
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/** List and grid for showing, hiding, resizing and placing each morj panel button. */
public class PanelConfigDialog extends BaseDialog{
    private static final float cellW = 104f, cellH = 68f;
    private static PanelConfigDialog instance;

    private final Table tabs = new Table();
    private final Table rows = new Table();
    private final Table colBar = new Table();
    private final Table board = new Table();
    private final TextField search = new TextField();
    private ScrollPane listPane, boardPane;
    private Cell<?> bodyCell, leftCell;
    private String query = "";
    private int editTab;
    private Table ghost;
    private Drawable cardBg, cardDimBg, cellBg, emptyBg, onBg, offBg, overBg;

    public static void open(){
        if(ui == null || ui.panelfragment == null) return;
        ui.panelfragment.registerPages();
        if(instance == null) instance = new PanelConfigDialog();
        instance.editTab = ui.panelfragment.currentTab();
        instance.show();
    }

    @Override
    public Dialog show(){
        Dialog dialog = super.show();
        fit();
        return dialog;
    }

    private PanelConfigDialog(){
        super("@client.morj.panel.title");
        addCloseButton();
        cont.top();
        Label hint = cont.add("@client.morj.panel.hint").growX().wrap().pad(2f).get();
        hint.setColor(Color.lightGray);
        cont.row();
        cont.add(legend()).growX().left().padBottom(6f).row();
        cont.add(tabs).growX().padBottom(4f).row();

        Table split = new Table();
        Table left = new Table();
        left.top();
        search.setMessageText(Core.bundle.get("client.morj.panel.search"));
        search.changed(() -> {
            String next = search.getText() == null ? "" : search.getText().trim();
            if(next.equals(query)) return;
            query = next;
            fillRows();
        });
        left.add(search).growX().height(36f).padBottom(4f).row();
        rows.top().left();
        listPane = left.pane(rows).grow().get();
        listPane.setScrollingDisabled(true, false);
        listPane.setFlickScroll(false);
        listPane.setCancelTouchFocus(false);
        listPane.setFadeScrollBars(false);
        leftCell = split.add(left).growY().padRight(8f);

        Table right = new Table();
        right.top();
        right.add(colBar).growX().padBottom(4f).row();
        board.top().left();
        boardPane = right.pane(board).grow().get();
        boardPane.setFlickScroll(false);
        boardPane.setCancelTouchFocus(false);
        boardPane.setFadeScrollBars(false);
        split.add(right).grow();
        bodyCell = cont.add(split).grow();

        buttons.button("@client.morj.panel.showall", Icon.eyeSmall, this::showAll);
        buttons.button("@client.morj.panel.reset", Icon.refreshSmall, () -> ui.showConfirm("@client.morj.panel.reset", "@client.morj.panel.reset.confirm", this::resetLayout));
        hidden(this::clearGhost);
        shown(() -> {
            rebuild();
            fit();
        });
        resized(this::fit);
    }

    private Table legend(){
        Table t = new Table();
        t.left();
        swatch(t, Pal.accent, "@client.morj.panel.legend.on");
        swatch(t, Color.darkGray, "@client.morj.panel.legend.off");
        swatch(t, Color.lightGray, "@client.morj.panel.legend.action");
        Image dot = new Image(Tex.whiteui);
        dot.setColor(Pal.accent);
        t.add(dot).size(6f).padLeft(12f).padRight(4f);
        Label right = t.add("@client.morj.panel.legend.right").get();
        right.setColor(Color.lightGray);
        return t;
    }

    private void swatch(Table t, Color color, String key){
        Image bar = new Image(Tex.whiteui);
        bar.setColor(color);
        t.add(bar).size(16f, 4f).padLeft(10f).padRight(4f);
        Label label = t.add(key).get();
        label.setColor(Color.lightGray);
    }

    private void fit(){
        float w = Mathf.clamp(Core.graphics.getWidth() * 0.74f, Math.min(860f, Core.graphics.getWidth() * 0.94f), 1240f);
        float h = Mathf.clamp(Core.graphics.getHeight() * 0.7f, Math.min(460f, Core.graphics.getHeight() * 0.82f), 880f);
        if(bodyCell != null) bodyCell.size(w, h);
        if(leftCell != null) leftCell.width(Math.min(440f, w * 0.48f));
        pack();
    }

    private void ensureBg(){
        if(cellBg != null) return;
        cellBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.1f);
        emptyBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.035f);
        cardBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.08f);
        cardDimBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.03f);
        onBg = ((TextureRegionDrawable)Tex.whiteui).tint(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.46f);
        offBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.06f);
        overBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.16f);
    }

    private void rebuild(){
        ensureBg();
        float listY = listPane == null ? 0f : listPane.getScrollPercentY();
        float boardY = boardPane == null ? 0f : boardPane.getScrollPercentY();
        fillTabs();
        fillCols();
        fillRows();
        fillBoard();
        Core.app.post(() -> {
            if(listPane != null) listPane.setScrollPercentY(listY);
            if(boardPane != null) boardPane.setScrollPercentY(boardY);
        });
    }

    private void fillTabs(){
        tabs.clear();
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle(Styles.flatt);
        style.up = offBg;
        style.over = overBg;
        style.down = onBg;
        style.checked = onBg;
        for(int i = 0; i < 5; i++){
            int tab = i;
            TextButton button = tabs.button(Core.bundle.get(tabKeys()[i]), style, () -> {
                editTab = tab;
                Core.app.post(this::rebuild);
            }).checked(b -> editTab == tab).height(34f).growX().pad(1f).get();
            button.getLabel().setFontScale(0.85f);
        }
    }

    private static String[] tabKeys(){
        return new String[]{
            "client.morj.tab.mine", "client.morj.tab.build", "client.morj.tab.view", "client.morj.tab.fight", "client.morj.tab.server"
        };
    }

    private void fillCols(){
        colBar.clear();
        colBar.left();
        int width = PanelLayout.cols(editTab);
        colBar.add("@client.morj.panel.cols").padRight(6f);
        colBar.button("-", Styles.cleart, () -> changeCols(-1)).size(32f).disabled(width <= 1);
        Label count = colBar.add(String.valueOf(width)).width(28f).center().get();
        count.setAlignment(Align.center);
        colBar.button("+", Styles.cleart, () -> changeCols(1)).size(32f).disabled(width >= 12).padRight(8f);
        Label hint = colBar.add("@client.morj.panel.colshint").growX().wrap().get();
        hint.setColor(Color.lightGray);
        hint.setFontScale(0.8f);
    }

    private void fillRows(){
        rows.clear();
        rows.top().left();
        if(ui == null || ui.panelfragment == null) return;
        Seq<PanelFragment.PanelIcon> shown = new Seq<>();
        Seq<PanelFragment.PanelIcon> hidden = new Seq<>();
        for(PanelFragment.PanelIcon icon : ui.panelfragment.tabIcons(editTab)){
            if(!matches(icon)) continue;
            if(PanelLayout.hidden(icon.id)) hidden.add(icon);
            else shown.add(icon);
        }
        if(shown.isEmpty() && hidden.isEmpty()){
            Label empty = rows.add("@client.morj.panel.nomatch").pad(8f).get();
            empty.setColor(Color.lightGray);
            return;
        }
        if(shown.any()){
            section(shown.size, "client.morj.panel.visible");
            for(PanelFragment.PanelIcon icon : shown) rows.add(iconRow(icon, false)).growX().padBottom(3f).row();
        }
        if(hidden.any()){
            section(hidden.size, "client.morj.panel.hidden");
            for(PanelFragment.PanelIcon icon : hidden) rows.add(iconRow(icon, true)).growX().padBottom(3f).row();
        }
    }

    private void section(int count, String key){
        Label label = rows.add(Core.bundle.format("client.morj.panel.section", Core.bundle.get(key), count)).left().padTop(4f).padBottom(3f).get();
        label.setColor(Pal.accent);
        rows.row();
    }

    private boolean matches(PanelFragment.PanelIcon icon){
        if(query.isEmpty()) return true;
        String q = query.toLowerCase();
        return plain(icon.caption).contains(q) || plain(icon.label).contains(q);
    }

    private static String plain(String text){
        if(text == null) return "";
        return text.replaceAll("\\[[^\\]]*\\]", "").toLowerCase();
    }

    private Table iconRow(PanelFragment.PanelIcon icon, boolean hidden){
        Table row = new Table(hidden ? cardDimBg : cardBg);
        row.margin(5f);
        row.touchable = Touchable.enabled;
        Table top = new Table();
        Image grip = new Image(Icon.moveSmall);
        grip.setColor(hidden ? Color.gray : Pal.accent);
        top.add(grip).size(14f).padRight(4f);
        if(icon.icon != null){
            Image image = new Image(icon.icon);
            image.setScaling(Scaling.fit);
            image.setColor(hidden ? Color.gray : Color.white);
            top.add(image).size(24f).padRight(6f);
        }
        Label name = top.add(icon.caption).growX().left().get();
        name.setFontScale(0.9f);
        name.setEllipsis(true);
        name.setAlignment(Align.left);
        name.setColor(hidden ? Color.gray : Color.white);
        if(icon.extra){
            Label extra = top.add("@client.morj.panel.rmb").padLeft(4f).get();
            extra.setFontScale(0.7f);
            extra.setColor(Pal.accent);
        }
        boolean hide = hidden;
        top.button(hide ? Icon.eyeOffSmall : Icon.eyeSmall, Styles.cleari, () -> {
            PanelLayout.setHidden(icon.id, !hide);
            ui.panelfragment.rebuild();
            Core.app.post(this::rebuild);
        }).size(30f).tooltip(hide ? "@client.morj.panel.show" : "@client.morj.panel.hide");
        float scale = PanelLayout.scale(icon.id);
        top.button("-", Styles.cleart, () -> scale(icon.id, -0.2f)).size(28f).disabled(scale <= 0.61f);
        Label pct = top.add(Mathf.round(scale * 100f) + "%").width(46f).get();
        pct.setAlignment(Align.center);
        pct.setFontScale(0.8f);
        top.button("+", Styles.cleart, () -> scale(icon.id, 0.2f)).size(28f).disabled(scale >= 1.99f);
        drag(row, icon, -1, -1);
        row.add(top).growX().row();
        if(icon.label != null && !icon.label.equals(icon.caption)){
            Label desc = row.add(icon.label).growX().left().wrap().padTop(2f).get();
            desc.setFontScale(0.75f);
            desc.setColor(Color.lightGray);
        }
        return row;
    }

    private void fillBoard(){
        board.clear();
        board.top().left();
        if(ui == null || ui.panelfragment == null) return;
        Seq<PanelFragment.PanelIcon> icons = ui.panelfragment.tabIcons(editTab);
        ObjectMap<String, PanelFragment.PanelIcon> byId = new ObjectMap<>();
        Seq<String> visible = new Seq<>();
        for(PanelFragment.PanelIcon icon : icons){
            byId.put(icon.id, icon);
            if(!PanelLayout.hidden(icon.id)) visible.add(icon.id);
        }
        int width = PanelLayout.cols(editTab);
        Seq<PanelLayout.Slot> slots = PanelLayout.resolve(visible, width);
        ObjectMap<String, PanelLayout.Slot> at = new ObjectMap<>();
        int maxC = width - 1, maxR = 0;
        for(PanelLayout.Slot slot : slots){
            at.put(slot.col + ":" + slot.row, slot);
            maxC = Math.max(maxC, slot.col);
            maxR = Math.max(maxR, slot.row);
        }
        int showC = Math.min(12, Math.max(width, maxC + 1) + (maxC + 1 < 12 ? 1 : 0));
        int showR = Math.min(14, Math.max(1, maxR + 1) + 1);
        for(int r = 0; r < showR; r++){
            for(int c = 0; c < showC; c++){
                PanelLayout.Slot slot = at.get(c + ":" + r);
                PanelFragment.PanelIcon icon = slot == null ? null : byId.get(slot.id);
                board.add(icon == null ? emptyCell(c, r) : iconCell(icon, c, r)).size(cellW, cellH).pad(2f);
            }
            board.row();
        }
    }

    private Table emptyCell(int col, int row){
        Table cell = new Table(emptyBg);
        cell.userObject = col + ":" + row;
        Label plus = cell.add("+").get();
        plus.setColor(new Color(1f, 1f, 1f, 0.28f));
        plus.setAlignment(Align.center);
        return cell;
    }

    private Table iconCell(PanelFragment.PanelIcon icon, int col, int row){
        Table cell = new Table(cellBg);
        cell.userObject = col + ":" + row;
        cell.touchable = Touchable.enabled;
        cell.top().margin(3f);
        if(icon.icon != null){
            Image image = new Image(icon.icon);
            image.setScaling(Scaling.fit);
            cell.add(image).size(22f).padTop(2f).row();
        }
        Label name = cell.add(icon.caption).growX().get();
        name.setFontScale(0.68f);
        name.setEllipsis(true);
        name.setAlignment(Align.center);
        cell.row();
        Label pct = cell.add(Mathf.round(PanelLayout.scale(icon.id) * 100f) + "%").get();
        pct.setFontScale(0.62f);
        pct.setColor(Color.lightGray);
        pct.setAlignment(Align.center);
        cell.addListener(new Tooltip(t -> {
            t.background(Styles.black6).margin(4f);
            t.add(icon.label + "\n[lightgray]" + Core.bundle.get("client.morj.panel.dragbtn")).wrap().width(240f);
        }));
        drag(cell, icon, col, row);
        return cell;
    }

    private void scale(String id, float delta){
        PanelLayout.setScale(id, PanelLayout.scale(id) + delta);
        ui.panelfragment.rebuild();
        Core.app.post(this::rebuild);
    }

    private void showAll(){
        boolean any = false;
        for(PanelFragment.PanelIcon icon : ui.panelfragment.tabIcons(editTab)){
            if(!PanelLayout.hidden(icon.id)) continue;
            PanelLayout.setHidden(icon.id, false);
            any = true;
        }
        if(!any) return;
        ui.panelfragment.rebuild();
        Core.app.post(this::rebuild);
    }

    private void resetLayout(){
        Core.settings.remove("morj-panel-hide");
        Core.settings.remove("morj-panel-order");
        Core.settings.remove("morj-panel-size");
        Core.settings.remove("morj-panel-pos");
        Core.settings.remove("morj-panel-cols");
        PanelLayout.reload();
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

    private void drag(Element handle, PanelFragment.PanelIcon icon, int col, int row){
        float[] start = new float[2];
        boolean[] moved = {false};
        handle.addListener(new InputListener(){
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(button != KeyCode.mouseLeft || event.targetActor instanceof Button) return false;
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
                    if(icon.icon != null){
                        Image image = new Image(icon.icon);
                        image.setScaling(Scaling.fit);
                        ghost.add(image).size(18f).padRight(4f);
                    }
                    ghost.add(icon.caption);
                    ghost.pack();
                    Core.scene.root.addChild(ghost);
                }
                ghost.setPosition(event.stageX + 12f, event.stageY - ghost.getHeight() - 8f);
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                clearGhost();
                if(!moved[0]) return;
                String target = cellAt(event.stageX, event.stageY);
                if(target == null) return;
                int colon = target.indexOf(':');
                if(colon <= 0) return;
                int toCol = Strings.parseInt(target.substring(0, colon), -1);
                int toRow = Strings.parseInt(target.substring(colon + 1), -1);
                if(toCol < 0 || toRow < 0 || (toCol == col && toRow == row)) return;
                if(PanelLayout.hidden(icon.id)) PanelLayout.setHidden(icon.id, false);
                PanelLayout.relocate(visibleIds(), PanelLayout.cols(editTab), icon.id, toCol, toRow);
                ui.panelfragment.rebuild();
                Core.app.post(PanelConfigDialog.this::rebuild);
            }
        });
    }

    private void clearGhost(){
        if(ghost == null) return;
        ghost.remove();
        ghost = null;
    }

    private String cellAt(float stageX, float stageY){
        for(Element child : board.getChildren()){
            if(!(child.userObject instanceof String key)) continue;
            child.stageToLocalCoordinates(Tmp.v1.set(stageX, stageY));
            if(Tmp.v1.x >= 0f && Tmp.v1.y >= 0f && Tmp.v1.x <= child.getWidth() && Tmp.v1.y <= child.getHeight()) return key;
        }
        return null;
    }
}
