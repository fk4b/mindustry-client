package mindustry.client.tool;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.client.tool.ToolApi.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/** Online schematic browser backed by mindustry-tool.com. */
public class ToolSchematicBrowserDialog extends BaseDialog{
    private static final int PAGE_SIZE = 20;
    private static final String[] SORTS = {"time_desc", "time_asc", "download-count_desc", "like_desc"};
    private static final String[] SORT_KEYS = {
        "client.tool.schem.sort.newest",
        "client.tool.schem.sort.oldest",
        "client.tool.schem.sort.downloads",
        "client.tool.schem.sort.likes"
    };

    private String query = "";
    private int sortIndex = 0;
    private int page = 0;
    private boolean verifiedOnly = true;
    private boolean loading;
    private String error;
    private final Seq<SchematicItem> items = new Seq<>();
    private final Seq<String> selectedTags = new Seq<>();
    private Table results;
    private TextField searchField;
    private Label status;

    public ToolSchematicBrowserDialog(){
        super("@client.tool.schem.title");
        shouldPause = true;
        addCloseButton();
        buttons.button("@schematics", Icon.copy, () -> {
            hide();
            ui.schematics.show();
        });
        buttons.button("@client.tool.schem.upload", Icon.upload, () -> {
            if(!Core.app.openURI(ToolApi.WEB + "/schematics?upload=true")){
                Core.app.setClipboardText(ToolApi.WEB + "/schematics?upload=true");
                ui.showInfoFade("@copied");
            }
        });
        makeButtonOverlay();
        shown(this::rebuild);
        onResize(this::rebuild);
    }

    void rebuild(){
        cont.clear();
        cont.top();

        cont.table(bar -> {
            bar.left();
            bar.image(Icon.zoom);
            searchField = bar.field(query, t -> query = t).growX().get();
            searchField.setMessageText("@schematic.search");
            searchField.setFilter((f, c) -> true);
            searchField.update(() -> {
                if(searchField.hasKeyboard() && Core.input.keyTap(arc.input.KeyCode.enter)){
                    searchNow();
                }
            });
            bar.button(Icon.ok, Styles.cleari, this::searchNow).size(48f).tooltip("@client.tool.schem.search");
            bar.button(b -> {
                b.add(Core.bundle.get(SORT_KEYS[sortIndex])).pad(6f);
            }, () -> {
                sortIndex = (sortIndex + 1) % SORTS.length;
                page = 0;
                rebuild();
                fetch();
            }).width(180f).tooltip("@client.tool.schem.sort");
            bar.check("@client.tool.schem.verified", verifiedOnly, v -> {
                verifiedOnly = v;
                page = 0;
                fetch();
            }).padLeft(8f);
        }).growX().pad(6f);
        cont.row();

        status = cont.add("").left().padLeft(10f).get();
        cont.row();

        results = new Table();
        cont.pane(results).grow().pad(4f);
        cont.row();

        cont.table(nav -> {
            nav.button(Icon.left, Styles.cleari, () -> {
                if(page > 0){
                    page--;
                    fetch();
                }
            }).disabled(b -> page <= 0 || loading).size(48f);
            nav.add("").update(l -> l.setText(Core.bundle.format("client.tool.schem.page", page + 1))).pad(8f);
            nav.button(Icon.right, Styles.cleari, () -> {
                if(items.size >= PAGE_SIZE){
                    page++;
                    fetch();
                }
            }).disabled(b -> items.size < PAGE_SIZE || loading).size(48f);
            nav.button(Icon.refresh, Styles.cleari, this::fetch).size(48f).padLeft(12f);
        }).pad(6f);

        if(Core.app.isDesktop() && searchField != null){
            Core.scene.setKeyboardFocus(searchField);
        }
        fetch();
    }

    void searchNow(){
        page = 0;
        fetch();
    }

    void fetch(){
        loading = true;
        error = null;
        if(status != null) status.setText("@client.tool.schem.loading");
        String verification = verifiedOnly ? "VERIFIED" : null;
        ToolApi.searchSchematics(page, PAGE_SIZE, SORTS[sortIndex], query, selectedTags, verification, list -> {
            loading = false;
            items.set(list);
            fillResults();
        }, msg -> {
            loading = false;
            error = msg;
            items.clear();
            fillResults();
        });
    }

    void fillResults(){
        if(results == null) return;
        results.clear();
        if(status != null){
            if(error != null) status.setText("[scarlet]" + error);
            else if(items.isEmpty()) status.setText("@client.tool.schem.empty");
            else status.setText(Core.bundle.format("client.tool.schem.count", items.size));
        }
        if(error != null){
            results.button("@client.tool.schem.retry", Icon.refresh, this::fetch).pad(20f);
            return;
        }

        int cols = Math.max(1, (int)(Core.graphics.getWidth() / Scl.scl(230f)));
        int i = 0;
        for(SchematicItem item : items){
            results.add(card(item)).pad(4f).size(210f, 250f);
            if(++i % cols == 0) results.row();
        }
    }

    Table card(SchematicItem item){
        return new Table(Tex.pane, t -> {
            t.top();
            Image img = new Image(Icon.image).setScaling(Scaling.fit);
            t.add(img).size(200f, 150f).pad(4f);
            ToolApi.loadImage(ToolApi.previewUrl(item.itemId), region -> img.setDrawable(new TextureRegionDrawable(region)));
            t.row();
            t.add(item.name == null || item.name.isEmpty() ? Core.bundle.get("client.tool.schem.unnamed") : item.name)
                .width(196f).ellipsis(true).labelAlign(Align.center).pad(2f);
            t.row();
            t.table(stats -> {
                stats.defaults().pad(2f);
                stats.image(Icon.upOpenSmall).size(16f).color(Pal.accent);
                stats.add(String.valueOf(item.likes)).padRight(8f);
                stats.image(Icon.downloadSmall).size(16f).color(Pal.accent);
                stats.add(String.valueOf(item.downloads));
            });
            t.row();
            t.table(btns -> {
                btns.defaults().size(42f);
                btns.button(Icon.info, Styles.cleari, () -> showDetails(item)).tooltip("@info.title");
                btns.button(Icon.copy, Styles.cleari, () -> copy(item)).tooltip("@client.tool.schem.copy");
                btns.button(Icon.download, Styles.cleari, () -> save(item)).tooltip("@client.tool.schem.save");
                btns.button(Icon.ok, Styles.cleari, () -> place(item)).visible(ToolSchematicBrowserDialog::canPlace)
                    .tooltip("@client.tool.schem.place");
            }).growX();
            img.clicked(() -> {
                if(state.isMenu()) showDetails(item);
                else place(item);
            });
        });
    }

    static boolean canPlace(){
        return state.isGame() && (state.rules.schematicsAllowed || Core.settings.getBool("forceallowschematics"));
    }

    void copy(SchematicItem item){
        ToolApi.downloadSchematic(item.itemId, s -> {
            Core.app.setClipboardText(schematics.writeBase64(s));
            ui.showInfoFade("@client.tool.schem.copied");
        }, this::fail);
    }

    void save(SchematicItem item){
        ToolApi.downloadSchematic(item.itemId, s -> {
            s.removeSteamID();
            if(item.name != null && !item.name.isEmpty()) s.tags.put("name", item.name);
            schematics.add(s);
            ui.showInfoFade("@client.tool.schem.saved");
        }, this::fail);
    }

    void place(SchematicItem item){
        if(!canPlace()){
            ui.showInfo("@schematic.disabled");
            return;
        }
        ToolApi.downloadSchematic(item.itemId, s -> {
            control.input.useSchematic(s);
            hide();
        }, this::fail);
    }

    public void showDetails(SchematicItem item){
        ToolApi.findSchematic(item.itemId, d -> {
            BaseDialog dlog = new BaseDialog(d.name == null || d.name.isEmpty() ? item.name : d.name);
            dlog.addCloseButton();
            dlog.cont.pane(p -> {
                p.top();
                Image img = new Image(Icon.image).setScaling(Scaling.fit);
                p.add(img).size(420f, 260f).pad(6f);
                ToolApi.loadImage(ToolApi.imageUrl(item.itemId), region -> img.setDrawable(new TextureRegionDrawable(region)));
                p.row();
                p.add(Core.bundle.format("client.tool.schem.stats", d.width, d.height, d.likes, d.downloads)).pad(4f);
                p.row();
                if(d.tags.any()){
                    p.add(d.tags.toString(", ")).color(Pal.accent).wrap().width(480f).pad(4f);
                    p.row();
                }
                if(d.description != null && !d.description.isEmpty()){
                    p.add(d.description).wrap().width(480f).left().pad(6f);
                    p.row();
                }
            }).grow();
            dlog.buttons.button("@client.tool.schem.copy", Icon.copy, () -> copy(item));
            dlog.buttons.button("@client.tool.schem.save", Icon.download, () -> save(item));
            if(state.isGame()) dlog.buttons.button("@client.tool.schem.place", Icon.ok, () -> {
                dlog.hide();
                place(item);
            });
            dlog.buttons.button("@client.tool.schem.openweb", Icon.link, () -> {
                String url = ToolApi.WEB + "/schematics/" + item.itemId;
                if(!Core.app.openURI(url)){
                    Core.app.setClipboardText(url);
                    ui.showInfoFade("@copied");
                }
            });
            dlog.show();
        }, this::fail);
    }

    void fail(String msg){
        ui.showErrorMessage(Core.bundle.format("client.tool.schem.error", msg == null ? "?" : msg));
    }
}
