package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.scene.style.Drawable;
import arc.scene.ui.Dialog;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Log;
import arc.util.Reflect;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.fs.Directory;
import mindustry.client.morj.mk.fs.Entry;
import mindustry.client.morj.mk.fs.FileSystem;
import mindustry.client.morj.mk.util.IntRef;
import mindustry.Vars;
import mindustry.gen.Tex;
import mindustry.ui.Fonts;

public class VirtualFileSystemDialog extends Dialog {
    public VirtualFileSystemDialog() {
        super();

        shown(this::onShow);
        hidden(() -> {
            cwd = null;
            main = null;
        });
        resized(this::build);
    }

    private VirtualFileSystemNewDialog newDialog = new VirtualFileSystemNewDialog();
    private Directory cwd;
    private Table main;
    private Table headerTable;
    private Table filesTable;
    private Table bottomTable;
    private Table infoTable;
    private Table controlsTable;

    public void entryCreated(Entry entry) {
        if (entry == null || cwd == null || entry.parent != cwd) return;
    }

    void onShow() {
        cwd = MVars.fileSystem;
        if (cwd == null) cwd = new FileSystem();

        build();
    }

    void build() {
        cont.clearChildren();

        int width = Core.graphics.getWidth();
        int height = Core.graphics.getHeight();

        if (width >= 600 && height >= 400) {
            width = Math.max(width / 5 * 4, 600);
            height = Math.max(height / 5 * 4, 400);
        }

        cont.table(Tex.button, t -> main = t).size(width, height).margin(8);

        main.table(t -> headerTable = t).height(30).growX().padBottom(4).row();
        filesTable = new Table();
        filesTable.fillParent = true;
        ScrollPane pane = new ScrollPane(filesTable);
        main.add(pane).grow().padBottom(4).row();
        main.table(t -> bottomTable = t).height(30).growX().row();

        final int width$const = width;
        main.table(pt -> {
            if (width$const > 300) {
                pt.table(t -> infoTable = t).left();
            }
            pt.table(t -> controlsTable = t).right();
        });

        controlsTable.button("@add", () -> {
            newDialog.show(cwd, entry -> {
                Log.info("Added entry");
                entryCreated(entry);
            });
        }).width(120).right();
        controlsTable.button("@close", this::hide).width(120).right().padLeft(4);

        final int filesPerRow = (width - 16 + 10) / (180 + 10) - 1;
        final IntRef filesRowAdded = new IntRef(0);

        cwd.each(entry -> {
            Cell<Table> cell = buildFile(entry);
            if (filesRowAdded.value++ >= filesPerRow) {
                filesRowAdded.value = 0;
                cell.row();
            }
        });
    }

    Cell<Table> buildFile(Entry entry) {
        Cell<Table> table = filesTable.table(Tex.button, t -> {
            int imsize = 180 - 30 - 16;
            t.table(t2 -> {
                Drawable icon = entry.icon();
                Image image = new Image(icon);

                float ratio = icon.getMinWidth() / icon.getMinHeight();

                if (ratio > 1) image.setScale(1, 1 / ratio);
                else image.setScale(ratio * 1, 1);

                t2.add(image).size(imsize, imsize).center();
            }).size(imsize).row();
            Label label = new Label((String) null);
            StringBuilder builder = Reflect.get(Label.class, label, "text");
            builder.setLength(0);
            builder.append(entry.name);
            label.setAlignment(Align.center);
            t.add(label).height(30).growX();
        }).size(180).pad(6);
        table.setBounds(0, 0, 180, 180);
        return table;
    }
}
