package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.func.Cons;
import arc.scene.ui.Dialog;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Cell;
import arc.util.Align;
import arc.util.Log;
import mindustry.client.morj.mk.fs.Directory;
import mindustry.client.morj.mk.fs.Entry;
import mindustry.Vars;

public class VirtualFileSystemNewDialog extends Dialog {
    private String filename;

    public Dialog show(Directory cwd, Cons<Entry> entryCreated) {
        build(cwd, entryCreated);
        return super.show();
    }

    void build(Directory cwd, Cons<Entry> entryCreated) {
        cont.clearChildren();

        filename = "New File";

        cont.label(() -> "@add").fillX().marginBottom(60).align(Align.center).row();
        cont.field(filename, s -> filename = s).fillX().row();
        cont.table(t -> {
            t.button("@mindurka.newfile", () -> {
                if (filename.isEmpty()) return;
                Entry entry = cwd.add(filename, new byte[0]);
                if (entry != null) entryCreated.get(entry);
                hide();
            }).width(140);
            Cell<TextButton> btn = t.button("@mindurka.newdir", () -> {
                if (filename.isEmpty()) return;
                Entry entry = cwd.mkdir(filename);
                if (entry != null) entryCreated.get(entry);
                hide();
            }).width(140);
            if (Core.graphics.getWidth() < 300) btn.row();
            t.button("@mindurka.importfile", () -> {
                final Directory dir = cwd;
                Vars.platform.showMultiFileChooser(fi -> {
                    Entry entry = dir.add(fi);
                    if (entry == null) {
                        Log.warn("Could not add entry");
                        return;
                    }
                    entryCreated.get(entry);
                }, "png");
                hide();
            }).width(140);
            t.button("@close", this::hide).width(140);
        }).fillX();
    }
}
