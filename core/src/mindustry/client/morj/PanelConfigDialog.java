package mindustry.client.morj;

import arc.*;
import arc.func.*;
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
import mindustry.ctype.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/**
 * Editor for the morj panel: tabs (add, rename, icon, order, delete), groups inside a tab,
 * and every button (visibility, size, hotkey, caption, icon, tab, group, grid cell).
 * Buttons are dragged from the list or the board onto a tab, a group title or a cell.
 */
public class PanelConfigDialog extends BaseDialog{
    private static final float cellW = 96f, cellH = 62f;
    private static PanelConfigDialog instance;

    private final Table tabBar = new Table();
    private final Table tabEdit = new Table();
    private final Table rows = new Table();
    private final Table board = new Table();
    private final TextField search = new TextField();
    private final Seq<Element> targets = new Seq<>();
    private ScrollPane listPane, boardPane;
    private Cell<?> bodyCell, leftCell;
    private String query = "";
    private boolean searchAll;
    private String editTab;
    private String hoverTarget;
    private Table ghost;
    private Drawable cardBg, cardDimBg, cellBg, emptyBg, onBg, offBg, overBg, dropBg, headBg, chipBg;
    private Slider titleSlider;
    private Label titleValue;
    private boolean titleLock;

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
        ensureBg();
        cont.top();
        Label hint = cont.add("@client.morj.panel.hint").growX().wrap().pad(2f).get();
        hint.setColor(Color.lightGray);
        hint.setFontScale(0.85f);
        cont.row();

        cont.add(tabBar).growX().padTop(4f).row();
        cont.add(tabEdit).growX().padTop(2f).padBottom(4f).row();

        titleSlider = new Slider(0.7f, 1.6f, 0.05f, false);
        Table scaleRow = new Table();
        scaleRow.left();
        scaleRow.add("@client.morj.panel.titlesize").color(Color.lightGray).padRight(8f);
        titleValue = new Label("100%");
        titleValue.setAlignment(Align.center);
        scaleRow.add(titleValue).width(52f);
        scaleRow.add(titleSlider).width(220f).padLeft(6f);
        scaleRow.add(legend()).growX().right().padLeft(16f);
        titleSlider.changed(() -> {
            if(titleLock) return;
            PanelLayout.setTitleScale(titleSlider.getValue());
            titleValue.setText(Mathf.round(PanelLayout.titleScale() * 100f) + "%");
            refreshPanel();
        });
        cont.add(scaleRow).growX().padBottom(6f).row();

        Table split = new Table();
        Table left = new Table();
        left.top();
        Table searchRow = new Table();
        search.setMessageText(Core.bundle.get("client.morj.panel.search"));
        search.changed(() -> {
            String next = search.getText() == null ? "" : search.getText().trim();
            if(next.equals(query)) return;
            query = next;
            fillRows();
        });
        searchRow.add(search).growX().height(36f);
        searchRow.check("@client.morj.panel.searchall", searchAll, b -> {
            searchAll = b;
            fillRows();
        }).padLeft(6f).get().getLabel().setFontScale(0.8f);
        left.add(searchRow).growX().padBottom(4f).row();
        rows.top().left();
        listPane = left.pane(rows).grow().get();
        listPane.setScrollingDisabled(true, false);
        listPane.setFlickScroll(false);
        listPane.setCancelTouchFocus(false);
        listPane.setFadeScrollBars(false);
        leftCell = split.add(left).growY().padRight(10f);

        Table right = new Table();
        right.top();
        board.top().left();
        boardPane = right.pane(board).grow().get();
        boardPane.setFlickScroll(false);
        boardPane.setCancelTouchFocus(false);
        boardPane.setFadeScrollBars(false);
        split.add(right).grow();
        bodyCell = cont.add(split).grow();

        buttons.button("@client.morj.panel.showall", Icon.eyeSmall, this::showAll);
        buttons.button("@client.morj.panel.add", Icon.add, this::addSetting);
        buttons.button("@client.morj.panel.addgroup", Icon.layers, this::addGroup);
        buttons.button("@client.morj.panel.reset", Icon.refreshSmall, () -> ui.showConfirm("@client.morj.panel.reset", "@client.morj.panel.reset.confirm", this::resetLayout));
        hidden(this::clearGhost);
        shown(() -> {
            rebuild();
            fit();
        });
        resized(this::fit);
    }

    // ------------------------------------------------------------------ chrome

    private Table legend(){
        Table t = new Table();
        t.right();
        swatch(t, Pal.accent, "@client.morj.panel.legend.on");
        swatch(t, Color.darkGray, "@client.morj.panel.legend.off");
        swatch(t, Color.lightGray, "@client.morj.panel.legend.action");
        Image dot = new Image(Tex.whiteui);
        dot.setColor(Pal.accent);
        t.add(dot).size(5f).padLeft(12f).padRight(4f);
        Label right = t.add("@client.morj.panel.legend.right").get();
        right.setColor(Color.lightGray);
        right.setFontScale(0.8f);
        return t;
    }

    private void swatch(Table t, Color color, String key){
        Image bar = new Image(Tex.whiteui);
        bar.setColor(color);
        t.add(bar).size(14f, 3f).padLeft(10f).padRight(4f);
        Label label = t.add(key).get();
        label.setColor(Color.lightGray);
        label.setFontScale(0.8f);
    }

    private void fit(){
        float w = Mathf.clamp(Core.graphics.getWidth() * 0.78f, Math.min(900f, Core.graphics.getWidth() * 0.95f), 1320f);
        float h = Mathf.clamp(Core.graphics.getHeight() * 0.66f, Math.min(420f, Core.graphics.getHeight() * 0.8f), 860f);
        if(bodyCell != null) bodyCell.size(w, h);
        if(leftCell != null) leftCell.width(Math.min(470f, w * 0.44f));
        pack();
    }

    private void ensureBg(){
        if(cellBg != null) return;
        TextureRegionDrawable white = (TextureRegionDrawable)Tex.whiteui;
        cellBg = white.tint(1f, 1f, 1f, 0.09f);
        emptyBg = white.tint(1f, 1f, 1f, 0.03f);
        cardBg = white.tint(1f, 1f, 1f, 0.065f);
        cardDimBg = white.tint(1f, 1f, 1f, 0.025f);
        onBg = white.tint(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.3f);
        offBg = white.tint(1f, 1f, 1f, 0.05f);
        overBg = white.tint(1f, 1f, 1f, 0.14f);
        dropBg = white.tint(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.35f);
        headBg = white.tint(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.1f);
        chipBg = white.tint(0f, 0f, 0f, 0.35f);
    }

    private void rebuild(){
        ensureBg();
        if(PanelLayout.tab(editTab) == null) editTab = PanelLayout.firstTab();
        syncTitle();
        targets.clear();
        float listY = listPane == null ? 0f : listPane.getScrollPercentY();
        float boardY = boardPane == null ? 0f : boardPane.getScrollPercentY();
        fillTabs();
        fillTabEdit();
        fillRows();
        fillBoard();
        Core.app.post(() -> {
            if(listPane != null) listPane.setScrollPercentY(listY);
            if(boardPane != null) boardPane.setScrollPercentY(boardY);
        });
    }

    private void refreshPanel(){
        if(ui != null && ui.panelfragment != null) ui.panelfragment.rebuild();
    }

    /** Applies a change to the panel and redraws this dialog on the next frame. */
    private void changed(){
        refreshPanel();
        Core.app.post(this::rebuild);
    }

    private void syncTitle(){
        if(titleSlider == null) return;
        titleLock = true;
        titleSlider.setValue(PanelLayout.titleScale());
        titleLock = false;
        if(titleValue != null) titleValue.setText(Mathf.round(PanelLayout.titleScale() * 100f) + "%");
    }

    /** Background that lights up while a dragged button hovers this drop target. */
    private void dropTarget(Table table, String key, Drawable normal){
        table.userObject = key;
        table.setBackground(normal);
        table.update(() -> table.setBackground(key.equals(hoverTarget) ? dropBg : normal));
        targets.add(table);
    }

    // ------------------------------------------------------------------ tabs

    private void fillTabs(){
        tabBar.clear();
        tabBar.left();
        Label caption = tabBar.add("@client.morj.panel.tabs").color(Pal.accent).padRight(8f).get();
        caption.setFontScale(0.85f);
        Table strip = new Table();
        strip.left();
        int n = 0;
        for(PanelLayout.Tab tab : PanelLayout.tabs()){
            String key = tab.key;
            boolean on = key.equals(editTab);
            Table chip = new Table();
            chip.touchable = Touchable.enabled;
            chip.margin(4f, 8f, 4f, 8f);
            Image image = new Image(PanelLayout.tabIcon(tab));
            image.setScaling(Scaling.fit);
            image.setColor(on ? Color.white : Color.lightGray);
            chip.add(image).size(18f).padRight(5f);
            Label name = chip.add(PanelLayout.tabName(tab)).get();
            name.setFontScale(0.85f);
            name.setColor(on ? Color.white : Color.lightGray);
            int count = ui.panelfragment.tabIcons(key).size;
            Label num = chip.add(String.valueOf(count)).padLeft(5f).get();
            num.setFontScale(0.7f);
            num.setColor(on ? Pal.accent : Color.gray);
            dropTarget(chip, "tab:" + key, on ? onBg : offBg);
            chip.clicked(() -> {
                editTab = key;
                Core.app.post(this::rebuild);
            });
            chip.addListener(new HandCursorListener());
            chip.addListener(new Tooltip(t -> t.background(Styles.black8).margin(4f).add("@client.morj.panel.droptab")));
            strip.add(chip).height(34f).pad(1f);
            if(++n % 8 == 0) strip.row();
        }
        strip.button(Icon.add, Styles.cleari, this::addTab).size(34f).pad(1f).tooltip("@client.morj.panel.addtab");
        tabBar.add(strip).growX().left();
    }

    private void fillTabEdit(){
        tabEdit.clear();
        tabEdit.left();
        PanelLayout.Tab tab = PanelLayout.tab(editTab);
        if(tab == null) return;
        String key = tab.key;
        tabEdit.setBackground(chipBg);
        tabEdit.margin(4f, 8f, 4f, 8f);

        tabEdit.button(PanelLayout.tabIcon(tab), Styles.cleari, 22f, () -> pickIcon(chosen -> {
            PanelLayout.setTabIcon(key, chosen);
            changed();
        })).size(36f).tooltip("@client.morj.panel.tabicon");
        Label name = tabEdit.add(PanelLayout.tabName(tab)).padLeft(4f).padRight(4f).get();
        name.setColor(Pal.accent);
        tabEdit.button(Icon.pencilSmall, Styles.cleari, () -> ui.showTextInput("@client.morj.panel.renametab", "@client.morj.panel.name", 24, PanelLayout.tabName(tab), text -> {
            PanelLayout.renameTab(key, text);
            changed();
        })).size(30f).tooltip("@client.morj.panel.renametab");

        divider(tabEdit);
        tabEdit.button(Icon.leftOpenSmall, Styles.cleari, () -> {
            PanelLayout.shiftTab(key, -1);
            changed();
        }).size(30f).tooltip("@client.morj.panel.tableft");
        tabEdit.button(Icon.rightOpenSmall, Styles.cleari, () -> {
            PanelLayout.shiftTab(key, 1);
            changed();
        }).size(30f).tooltip("@client.morj.panel.tabright");

        divider(tabEdit);
        stepper(tabEdit, "@client.morj.panel.cols", tab.cols, 1, 12, value -> {
            Seq<String> ids = new Seq<>();
            for(PanelFragment.PanelIcon icon : ui.panelfragment.tabIcons(key)) ids.add(icon.id);
            PanelLayout.reflow(ids, key, value);
            changed();
        });
        divider(tabEdit);
        stepper(tabEdit, "@client.morj.panel.groupcols", tab.groupCols, 1, 3, value -> {
            PanelLayout.setGroupCols(key, value);
            changed();
        });

        tabEdit.add().growX();
        if(!PanelLayout.builtinTab(key)){
            tabEdit.button("@client.morj.panel.deltab", Icon.trashSmall, Styles.cleart, () ->
                ui.showConfirm("@client.morj.panel.deltab", Core.bundle.get("client.morj.panel.deltab.confirm").replace("{0}", PanelLayout.tabName(tab)), () -> {
                    PanelLayout.removeTab(key);
                    editTab = PanelLayout.firstTab();
                    changed();
                })).height(32f).padLeft(8f);
        }else{
            Label builtin = tabEdit.add("@client.morj.panel.builtintab").padLeft(8f).get();
            builtin.setColor(Color.gray);
            builtin.setFontScale(0.75f);
        }
    }

    private void divider(Table t){
        t.image(Tex.whiteui).color(new Color(1f, 1f, 1f, 0.12f)).width(1f).height(24f).padLeft(8f).padRight(8f);
    }

    private void stepper(Table t, String title, int value, int min, int max, Intc apply){
        Label label = t.add(title).padRight(4f).get();
        label.setColor(Color.lightGray);
        label.setFontScale(0.85f);
        t.button("-", Styles.cleart, () -> apply.get(Mathf.clamp(value - 1, min, max))).size(28f).disabled(value <= min);
        Label count = t.add(String.valueOf(value)).width(24f).get();
        count.setAlignment(Align.center);
        t.button("+", Styles.cleart, () -> apply.get(Mathf.clamp(value + 1, min, max))).size(28f).disabled(value >= max);
    }

    private void addTab(){
        ui.showTextInput("@client.morj.panel.addtab", "@client.morj.panel.name", 24, "", text -> {
            editTab = PanelLayout.addTab(text);
            changed();
        });
    }

    // ------------------------------------------------------------------ list on the left

    private void fillRows(){
        rows.clear();
        rows.top().left();
        if(ui == null || ui.panelfragment == null) return;
        boolean everywhere = searchAll && !query.isEmpty();
        Seq<PanelFragment.PanelIcon> source = new Seq<>();
        if(everywhere){
            for(PanelLayout.Tab tab : PanelLayout.tabs()) source.addAll(ui.panelfragment.tabIcons(tab.key));
        }else{
            source.addAll(ui.panelfragment.tabIcons(editTab));
        }
        Seq<PanelFragment.PanelIcon> shown = new Seq<>();
        Seq<PanelFragment.PanelIcon> hidden = new Seq<>();
        for(PanelFragment.PanelIcon icon : source){
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
            for(PanelFragment.PanelIcon icon : shown) rows.add(iconRow(icon, false, everywhere)).growX().padBottom(3f).row();
        }
        if(hidden.any()){
            section(hidden.size, "client.morj.panel.hidden");
            for(PanelFragment.PanelIcon icon : hidden) rows.add(iconRow(icon, true, everywhere)).growX().padBottom(3f).row();
        }
    }

    private void section(int count, String key){
        Label label = rows.add(Core.bundle.get("client.morj.panel.section").replace("{0}", Core.bundle.get(key)).replace("{1}", String.valueOf(count))).left().padTop(4f).padBottom(3f).get();
        label.setColor(Pal.accent);
        label.setFontScale(0.85f);
        rows.row();
    }

    private boolean matches(PanelFragment.PanelIcon icon){
        if(query.isEmpty()) return true;
        String q = query.toLowerCase();
        return plain(icon.caption).contains(q) || plain(icon.label).contains(q) || plain(icon.shownCaption()).contains(q);
    }

    private static String plain(String text){
        if(text == null) return "";
        return text.replaceAll("\\[[^\\]]*\\]", "").toLowerCase();
    }

    private Table iconRow(PanelFragment.PanelIcon icon, boolean hidden, boolean showTab){
        Table row = new Table(hidden ? cardDimBg : cardBg);
        row.margin(5f, 6f, 5f, 6f);
        row.touchable = Touchable.enabled;

        Table top = new Table();
        Image grip = new Image(Icon.moveSmall);
        grip.setColor(hidden ? Color.gray : Pal.accent);
        top.add(grip).size(14f).padRight(4f);
        ImageButton iconButton = top.button(icon.shownIcon(), Styles.cleari, 22f, () -> pickIcon(chosen -> {
            PanelLayout.setIcon(icon.id, chosen);
            changed();
        })).size(30f).tooltip("@client.morj.panel.btnicon").get();
        iconButton.getImage().setColor(hidden ? Color.gray : Color.white);
        Label name = top.add(icon.shownCaption()).growX().left().padLeft(4f).get();
        name.setFontScale(0.9f);
        name.setEllipsis(true);
        name.setAlignment(Align.left);
        name.setColor(hidden ? Color.gray : Color.white);
        top.button(Icon.pencilSmall, Styles.cleari, () -> ui.showTextInput("@client.morj.panel.caption", "@client.morj.panel.captionhint", 18, icon.shownCaption(), text -> {
            PanelLayout.setCaption(icon.id, text == null || text.trim().equals(icon.caption) ? null : text);
            changed();
        })).size(28f).tooltip("@client.morj.panel.caption");
        if(icon.extra){
            Label extra = top.add("@client.morj.panel.rmb").padLeft(2f).get();
            extra.setFontScale(0.65f);
            extra.setColor(Pal.accent);
        }
        boolean hide = hidden;
        top.button(hide ? Icon.eyeOffSmall : Icon.eyeSmall, Styles.cleari, () -> {
            PanelLayout.setHidden(icon.id, !hide);
            changed();
        }).size(28f).tooltip(hide ? "@client.morj.panel.show" : "@client.morj.panel.hide");
        float scale = PanelLayout.scale(icon.id);
        top.button("-", Styles.cleart, () -> scale(icon.id, -0.2f)).size(26f).disabled(scale <= 0.61f);
        Label pct = top.add(Mathf.round(scale * 100f) + "%").width(42f).get();
        pct.setAlignment(Align.center);
        pct.setFontScale(0.78f);
        top.button("+", Styles.cleart, () -> scale(icon.id, 0.2f)).size(26f).disabled(scale >= 1.99f);
        drag(row, icon);
        row.add(top).growX().row();

        if(icon.label != null && !icon.label.equals(icon.caption)){
            Label desc = row.add(icon.label).growX().left().wrap().padTop(2f).padLeft(18f).get();
            desc.setFontScale(0.72f);
            desc.setColor(Color.lightGray);
            row.row();
        }

        Table chips = new Table();
        chips.left();
        String bound = PanelLayout.keyOf(icon.id);
        chip(chips, Icon.terminalSmall, bound == null ? Core.bundle.get("client.morj.panel.nokey") : keyName(bound),
            "@client.morj.panel.key", () -> captureKey(icon.id));
        PanelLayout.Tab tab = PanelLayout.tab(icon.tab());
        chip(chips, PanelLayout.tabIcon(tab), PanelLayout.tabName(tab), "@client.morj.panel.movetab", () -> chooseTab(icon));
        String group = icon.group();
        chip(chips, Icon.layersSmall, PanelLayout.groupTitle(PanelLayout.group(group)), "@client.morj.panel.movegroup", () -> chooseGroup(icon));
        row.add(chips).left().padTop(3f).padLeft(14f);
        return row;
    }

    private void chip(Table t, Drawable icon, String text, String tip, Runnable click){
        Button.ButtonStyle style = new Button.ButtonStyle();
        style.up = chipBg;
        style.over = overBg;
        style.down = onBg;
        Button button = new Button(style);
        Image image = new Image(icon);
        image.setScaling(Scaling.fit);
        button.add(image).size(14f).padRight(4f);
        Label label = new Label(text);
        label.setFontScale(0.72f);
        label.setEllipsis(true);
        button.add(label).maxWidth(110f);
        button.margin(2f, 6f, 2f, 6f);
        button.clicked(click);
        button.addListener(new Tooltip(tt -> tt.background(Styles.black8).margin(4f).add(tip)));
        t.add(button).height(24f).padRight(4f);
    }

    private static String keyName(String raw){
        try{
            return KeyCode.valueOf(raw).getName();
        }catch(IllegalArgumentException ignored){
            return raw;
        }
    }

    private void chooseTab(PanelFragment.PanelIcon icon){
        Seq<String> names = new Seq<>();
        Seq<String> keys = new Seq<>();
        Seq<Drawable> pics = new Seq<>();
        for(PanelLayout.Tab tab : PanelLayout.tabs()){
            names.add(PanelLayout.tabName(tab));
            keys.add(tab.key);
            pics.add(PanelLayout.tabIcon(tab));
        }
        choose("@client.morj.panel.movetab", names, pics, i -> {
            PanelLayout.moveToTab(icon.id, keys.get(i), icon.homeTab);
            if(PanelLayout.hidden(icon.id)) PanelLayout.setHidden(icon.id, false);
            changed();
        });
    }

    private void chooseGroup(PanelFragment.PanelIcon icon){
        String tab = icon.tab();
        Seq<String> names = new Seq<>();
        Seq<String> ids = new Seq<>();
        Seq<Drawable> pics = new Seq<>();
        names.add(Core.bundle.get("client.morj.panel.nogroup"));
        ids.add("");
        pics.add(Icon.cancelSmall);
        for(PanelLayout.Group group : PanelLayout.groups(tab)){
            names.add(PanelLayout.groupTitle(group));
            ids.add(group.id);
            pics.add(Icon.layersSmall);
        }
        names.add(Core.bundle.get("client.morj.panel.addgroup"));
        ids.add((String)null);
        pics.add(Icon.addSmall);
        choose("@client.morj.panel.movegroup", names, pics, i -> {
            String id = ids.get(i);
            if(id == null){
                ui.showTextInput("@client.morj.panel.addgroup", "@client.morj.panel.name", 24, "", text -> {
                    String made = PanelLayout.addGroup(tab, text);
                    if(made != null) PanelLayout.moveToGroup(icon.id, made);
                    changed();
                });
                return;
            }
            PanelLayout.moveToGroup(icon.id, id);
            changed();
        });
    }

    /** Small list dialog. */
    private void choose(String title, Seq<String> names, Seq<Drawable> pics, Intc picked){
        BaseDialog dialog = new BaseDialog(title);
        Table list = new Table();
        list.top();
        for(int i = 0; i < names.size; i++){
            int index = i;
            Button button = new Button(Styles.flatt);
            if(pics.get(i) != null){
                Image image = new Image(pics.get(i));
                image.setScaling(Scaling.fit);
                button.add(image).size(22f).padLeft(10f).padRight(10f);
            }
            Label label = new Label(names.get(i));
            button.add(label).growX().left();
            button.clicked(() -> {
                dialog.hide();
                picked.get(index);
            });
            list.add(button).growX().height(44f).padBottom(2f).row();
        }
        dialog.cont.pane(list).width(380f).maxHeight(520f);
        dialog.addCloseButton();
        dialog.show();
    }

    // ------------------------------------------------------------------ board on the right

    private void fillBoard(){
        board.clear();
        board.top().left();
        if(ui == null || ui.panelfragment == null) return;
        PanelLayout.Tab tab = PanelLayout.tab(editTab);
        if(tab == null) return;
        int columns = Math.max(1, tab.cols);
        int inner = tab.groupCols > 1 ? Math.min(columns, 4) : columns;

        Seq<String> loose = new Seq<>();
        ObjectMap<String, Seq<String>> byGroup = new ObjectMap<>();
        ObjectMap<String, PanelFragment.PanelIcon> byId = new ObjectMap<>();
        for(PanelFragment.PanelIcon icon : ui.panelfragment.tabIcons(editTab)){
            byId.put(icon.id, icon);
            if(PanelLayout.hidden(icon.id)) continue;
            String group = icon.group();
            if(group.isEmpty()) loose.add(icon.id);
            else byGroup.get(group, Seq::new).add(icon.id);
        }

        groupBlock(null, loose, byId, columns);
        for(PanelLayout.Group group : PanelLayout.groups(editTab)){
            Seq<String> ids = byGroup.get(group.id);
            groupBlock(group, ids == null ? new Seq<>() : ids, byId, inner);
        }
        board.button("@client.morj.panel.addgroup", Icon.layers, Styles.cleart, this::addGroup).left().height(36f).padTop(6f).row();
    }

    private void groupBlock(PanelLayout.Group group, Seq<String> ids, ObjectMap<String, PanelFragment.PanelIcon> byId, int width){
        String gid = group == null ? "" : group.id;
        Table head = new Table();
        head.left();
        head.margin(3f, 6f, 3f, 6f);
        head.touchable = Touchable.enabled;
        dropTarget(head, "group:" + gid, headBg);
        if(group != null){
            boolean collapsed = group.collapsed;
            head.button(collapsed ? Icon.rightOpenSmall : Icon.downOpenSmall, Styles.cleari, () -> {
                PanelLayout.setCollapsed(group.id, !collapsed);
                changed();
            }).size(26f).tooltip("@client.morj.panel.fold");
        }else{
            Image none = new Image(Icon.gridSmall);
            none.setColor(Color.lightGray);
            head.add(none).size(16f).padLeft(5f).padRight(5f);
        }
        Label title = head.add(PanelLayout.groupTitle(group)).padLeft(2f).get();
        title.setColor(group == null ? Color.lightGray : Pal.accent);
        Label count = head.add(String.valueOf(ids.size)).padLeft(6f).get();
        count.setColor(Color.gray);
        count.setFontScale(0.75f);
        head.add().growX();
        if(group != null){
            head.button(Icon.pencilSmall, Styles.cleari, () -> ui.showTextInput("@client.morj.panel.renamegroup", "@client.morj.panel.name", 24, PanelLayout.groupTitle(group), text -> {
                PanelLayout.renameGroup(group.id, text);
                changed();
            })).size(26f).tooltip("@client.morj.panel.renamegroup");
            head.button(Icon.upOpenSmall, Styles.cleari, () -> {
                PanelLayout.shiftGroup(group.id, -1);
                changed();
            }).size(26f).tooltip("@client.morj.panel.groupup");
            head.button(Icon.downOpenSmall, Styles.cleari, () -> {
                PanelLayout.shiftGroup(group.id, 1);
                changed();
            }).size(26f).tooltip("@client.morj.panel.groupdown");
            head.button(Icon.trashSmall, Styles.cleari, () -> ui.showConfirm("@client.morj.panel.delgroup", Core.bundle.get("client.morj.panel.delgroup.confirm").replace("{0}", PanelLayout.groupTitle(group)), () -> {
                PanelLayout.removeGroup(group.id);
                changed();
            })).size(26f).tooltip("@client.morj.panel.delgroup");
        }
        board.add(head).growX().padTop(6f).padBottom(2f).row();

        Seq<PanelLayout.Slot> slots = PanelLayout.resolve(ids, width);
        ObjectMap<String, PanelLayout.Slot> at = new ObjectMap<>();
        int maxC = width - 1, maxR = -1;
        for(PanelLayout.Slot slot : slots){
            at.put(slot.col + ":" + slot.row, slot);
            maxC = Math.max(maxC, slot.col);
            maxR = Math.max(maxR, slot.row);
        }
        int showC = Math.min(12, Math.max(width, maxC + 1));
        int showR = Math.min(14, maxR + 2);
        Table grid = new Table();
        grid.left().top();
        for(int r = 0; r < showR; r++){
            for(int c = 0; c < showC; c++){
                PanelLayout.Slot slot = at.get(c + ":" + r);
                PanelFragment.PanelIcon icon = slot == null ? null : byId.get(slot.id);
                String key = "cell:" + gid + ":" + c + ":" + r;
                grid.add(icon == null ? emptyCell(key) : iconCell(icon, key)).size(cellW, cellH).pad(2f);
            }
            grid.row();
        }
        board.add(grid).left().padLeft(4f).row();
    }

    private Table emptyCell(String key){
        Table cell = new Table();
        dropTarget(cell, key, emptyBg);
        Label plus = cell.add("+").get();
        plus.setColor(new Color(1f, 1f, 1f, 0.22f));
        plus.setAlignment(Align.center);
        return cell;
    }

    private Table iconCell(PanelFragment.PanelIcon icon, String key){
        Table cell = new Table();
        dropTarget(cell, key, cellBg);
        cell.touchable = Touchable.enabled;
        cell.top().margin(3f);
        Drawable drawable = icon.shownIcon();
        if(drawable != null){
            Image image = new Image(drawable);
            image.setScaling(Scaling.fit);
            cell.add(image).size(22f).padTop(3f).row();
        }
        Label name = cell.add(icon.shownCaption()).growX().get();
        name.setFontScale(0.66f);
        name.setEllipsis(true);
        name.setAlignment(Align.center);
        cell.row();
        String bound = PanelLayout.keyOf(icon.id);
        String info = Mathf.round(PanelLayout.scale(icon.id) * 100f) + "%" + (bound == null ? "" : "  [accent]" + keyName(bound));
        Label pct = cell.add(info).get();
        pct.setFontScale(0.58f);
        pct.setColor(Color.lightGray);
        pct.setAlignment(Align.center);
        cell.addListener(new Tooltip(t -> {
            t.background(Styles.black8).margin(4f);
            t.add(icon.label + "\n[lightgray]" + Core.bundle.get("client.morj.panel.dragbtn")).wrap().width(240f);
        }));
        drag(cell, icon);
        return cell;
    }

    // ------------------------------------------------------------------ actions

    private void scale(String id, float delta){
        PanelLayout.setScale(id, PanelLayout.scale(id) + delta);
        changed();
    }

    private void showAll(){
        boolean any = false;
        for(PanelFragment.PanelIcon icon : ui.panelfragment.tabIcons(editTab)){
            if(!PanelLayout.hidden(icon.id)) continue;
            PanelLayout.setHidden(icon.id, false);
            any = true;
        }
        if(any) changed();
    }

    private void addGroup(){
        String tab = editTab;
        ui.showTextInput("@client.morj.panel.addgroup", "@client.morj.panel.name", 24, "", text -> {
            PanelLayout.addGroup(tab, text);
            changed();
        });
    }

    private void resetLayout(){
        PanelLayout.resetAll();
        ui.panelfragment.reregister();
        changed();
    }

    private void captureKey(String id){
        BaseDialog dialog = new BaseDialog("@client.morj.panel.keytitle");
        dialog.cont.add("@client.morj.panel.keywait").growX().wrap().width(360f).pad(8f);
        dialog.addListener(new InputListener(){
            @Override
            public boolean keyDown(InputEvent event, KeyCode code){
                if(code == KeyCode.escape || code == KeyCode.back){
                    PanelLayout.setKey(id, null);
                    dialog.hide();
                    changed();
                    return true;
                }
                if(code == null || code == KeyCode.unset || code == KeyCode.anyKey) return false;
                String name = code.name();
                if(name.startsWith("mouse") || name.startsWith("scroll") || name.startsWith("controller")) return false;
                PanelLayout.setKey(id, name);
                dialog.hide();
                changed();
                return true;
            }
        });
        dialog.addCloseButton();
        dialog.shown(() -> Core.scene.setKeyboardFocus(dialog));
        dialog.show();
    }

    private void addSetting(){
        if(ui == null || ui.settings == null || ui.panelfragment == null) return;
        BaseDialog dialog = new BaseDialog("@client.morj.panel.add");
        TextField field = new TextField();
        field.setMessageText(Core.bundle.get("client.morj.panel.search"));
        Table list = new Table();
        list.top().left();
        String tab = editTab;
        Runnable fill = () -> {
            list.clear();
            String q = field.getText() == null ? "" : field.getText().trim().toLowerCase();
            int shown = 0;
            for(SettingsMenuDialog.SettingsTable.Setting setting : ui.settings.allSettings()){
                if(setting.title == null || setting.name == null || hasExtra(setting.name)) continue;
                if(!q.isEmpty() && !setting.title.toLowerCase().contains(q) && !setting.name.toLowerCase().contains(q)) continue;
                if(shown++ >= 80) break;
                String name = setting.name;
                TextButton button = list.button(setting.title, Styles.cleart, () -> {
                    PanelLayout.addExtra(name, tab);
                    ui.panelfragment.reregister();
                    dialog.hide();
                    changed();
                }).growX().left().padBottom(1f).minHeight(32f).get();
                button.getLabel().setWrap(true);
                button.getLabel().setAlignment(Align.left);
                list.row();
            }
            if(shown == 0){
                Label empty = list.add("@client.morj.panel.nomatch").pad(8f).get();
                empty.setColor(Color.lightGray);
            }
        };
        field.changed(fill);
        dialog.cont.add(field).growX().height(36f).padBottom(4f).row();
        dialog.cont.pane(list).grow().size(520f, 420f);
        dialog.shown(fill);
        dialog.addCloseButton();
        dialog.show();
    }

    private static boolean hasExtra(String name){
        String prefix = name + "\t";
        for(String rec : PanelLayout.extras()) if(rec.startsWith(prefix)) return true;
        return false;
    }

    // ------------------------------------------------------------------ icon picker

    private static final String[] pickerKinds = {"icon", "item", "unit", "block", "liquid"};

    /** Grid of game icons and content sprites. Passes null for "default". */
    private void pickIcon(Cons<String> done){
        BaseDialog dialog = new BaseDialog("@client.morj.panel.pickicon");
        TextField field = new TextField();
        field.setMessageText(Core.bundle.get("client.morj.panel.search"));
        Table grid = new Table();
        grid.top().left();
        String[] kind = {"icon"};
        Table kinds = new Table();
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            grid.clear();
            String q = field.getText() == null ? "" : field.getText().trim().toLowerCase();
            Seq<String> keys = new Seq<>();
            switch(kind[0]){
                case "icon" -> {
                    for(String name : PanelLayout.iconNames()) keys.add("icon:" + name);
                }
                case "item" -> {
                    for(Item item : content.items()) keys.add("item:" + item.name);
                }
                case "unit" -> {
                    for(UnitType unit : content.units()) if(!unit.isHidden()) keys.add("unit:" + unit.name);
                }
                case "block" -> {
                    for(Block block : content.blocks()) if(!block.isHidden() && block.isVisible()) keys.add("block:" + block.name);
                }
                case "liquid" -> {
                    for(Liquid liquid : content.liquids()) keys.add("liquid:" + liquid.name);
                }
                default -> {}
            }
            int n = 0;
            for(String key : keys){
                if(!q.isEmpty() && !key.toLowerCase().contains(q) && !localized(key).contains(q)) continue;
                Drawable drawable = PanelLayout.icon(key);
                if(drawable == null) continue;
                if(n >= 400) break;
                ImageButton button = grid.button(drawable, Styles.flati, 30f, () -> {
                    dialog.hide();
                    done.get(key);
                }).size(44f).pad(2f).get();
                button.addListener(new Tooltip(t -> t.background(Styles.black8).margin(4f).add(localized(key))));
                if(++n % 12 == 0) grid.row();
            }
            if(n == 0) grid.add("@client.morj.panel.nomatch").color(Color.lightGray).pad(8f);
        };
        for(String k : pickerKinds){
            kinds.button(Core.bundle.get("client.morj.panel.kind." + k), Styles.togglet, () -> {
                kind[0] = k;
                fill[0].run();
            }).checked(b -> kind[0].equals(k)).height(36f).growX().pad(1f);
        }
        field.changed(fill[0]);
        dialog.cont.add(kinds).growX().row();
        dialog.cont.add(field).growX().height(36f).padTop(4f).padBottom(4f).row();
        dialog.cont.pane(grid).size(600f, 440f);
        dialog.buttons.button("@client.morj.panel.defaulticon", Icon.refreshSmall, () -> {
            dialog.hide();
            done.get(null);
        }).size(220f, 54f);
        dialog.addCloseButton();
        dialog.shown(fill[0]);
        dialog.show();
    }

    private static String localized(String key){
        int sep = key.indexOf(':');
        String type = key.substring(0, sep), name = key.substring(sep + 1);
        UnlockableContent c = switch(type){
            case "item" -> content.item(name);
            case "unit" -> content.unit(name);
            case "block" -> content.block(name);
            case "liquid" -> content.liquid(name);
            default -> null;
        };
        return (c != null ? c.localizedName : name).toLowerCase();
    }

    // ------------------------------------------------------------------ drag and drop

    private void drag(Element handle, PanelFragment.PanelIcon icon){
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
                    Drawable drawable = icon.shownIcon();
                    if(drawable != null){
                        Image image = new Image(drawable);
                        image.setScaling(Scaling.fit);
                        ghost.add(image).size(18f).padRight(4f);
                    }
                    ghost.add(icon.shownCaption());
                    ghost.pack();
                    Core.scene.root.addChild(ghost);
                }
                ghost.setPosition(event.stageX + 12f, event.stageY - ghost.getHeight() - 8f);
                hoverTarget = targetAt(event.stageX, event.stageY);
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                clearGhost();
                if(!moved[0]) return;
                String target = targetAt(event.stageX, event.stageY);
                hoverTarget = null;
                if(target != null) drop(icon, target);
            }
        });
    }

    private void drop(PanelFragment.PanelIcon icon, String target){
        if(PanelLayout.hidden(icon.id)) PanelLayout.setHidden(icon.id, false);
        if(target.startsWith("tab:")){
            String tab = target.substring(4);
            if(!tab.equals(icon.tab())) PanelLayout.moveToTab(icon.id, tab, icon.homeTab);
            changed();
            return;
        }
        if(!editTab.equals(icon.tab())) PanelLayout.moveToTab(icon.id, editTab, icon.homeTab);
        if(target.startsWith("group:")){
            String group = target.substring(6);
            if(!group.equals(icon.group())) PanelLayout.moveToGroup(icon.id, group);
            changed();
            return;
        }
        if(target.startsWith("cell:")){
            String[] parts = target.split(":");
            if(parts.length != 4) return;
            String group = parts[1];
            int col = Strings.parseInt(parts[2], -1), row = Strings.parseInt(parts[3], -1);
            if(col < 0 || row < 0) return;
            if(!group.equals(icon.group())) PanelLayout.moveToGroup(icon.id, group);
            PanelLayout.Tab tab = PanelLayout.tab(editTab);
            int columns = tab == null ? 4 : (group.isEmpty() || tab.groupCols <= 1 ? tab.cols : Math.min(tab.cols, 4));
            Seq<String> ids = new Seq<>();
            for(PanelFragment.PanelIcon other : ui.panelfragment.tabIcons(editTab)){
                if(PanelLayout.hidden(other.id) || other.id.equals(icon.id)) continue;
                if(other.group().equals(group)) ids.add(other.id);
            }
            ids.add(icon.id);
            PanelLayout.place(ids, columns, icon.id, col, row);
            changed();
        }
    }

    private void clearGhost(){
        hoverTarget = null;
        if(ghost == null) return;
        ghost.remove();
        ghost = null;
    }

    private String targetAt(float stageX, float stageY){
        for(Element child : targets){
            if(!(child.userObject instanceof String key) || child.getScene() == null || !child.visible) continue;
            child.stageToLocalCoordinates(Tmp.v1.set(stageX, stageY));
            if(Tmp.v1.x >= 0f && Tmp.v1.y >= 0f && Tmp.v1.x <= child.getWidth() && Tmp.v1.y <= child.getHeight()) return key;
        }
        return null;
    }
}
