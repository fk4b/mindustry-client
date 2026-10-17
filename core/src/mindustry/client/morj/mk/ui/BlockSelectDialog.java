package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.func.Boolf;
import arc.func.Cons;
import arc.input.KeyCode;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;

import java.lang.ref.WeakReference;

public class BlockSelectDialog extends BaseDialog {
    Boolf<Block> filter;
    Cons<Block> set;
    WeakReference<Table> filteredContent;
    Block chosen;

    public BlockSelectDialog(String title) {
        super(title);

        shown(this::build);
        hidden(cont::clear);

        keyDown(KeyCode.escape, this::hide);
    }

    private void build() {
        cont.clear();

        cont.table(t -> {
            t.field("", this::rebuildItems).row();
            Table content = new Table();
            content.setFillParent(true);
            filteredContent = new WeakReference<>(content);
            ScrollPane scroll = new ScrollPane(filteredContent.get());
            t.add(scroll).fillX().row();
            rebuildItems("");
        });
    }

    private void rebuildItems(String filter) {
        Table table = filteredContent.get();
        if (table == null) return;

        table.clear();

        final int cols;
        if (Core.graphics.isPortrait()) {
            cols = Math.max(4, (int)((Core.graphics.getWidth() / Scl.scl() - 100f) / 50f));
        } else {
            cols = Math.max(4, (int)((Core.graphics.getWidth() / Scl.scl() - 300f) / 50f / 2));
        }

        int i = 0;
        for (Block block : Vars.content.blocks()) {
            if (block.uiIcon == null) continue;
            if (!block.name.contains(filter) || !this.filter.get(block)) continue;

            ImageButton button = new ImageButton(Tex.whiteui, Styles.clearNoneTogglei);
            button.getStyle().imageUp = new TextureRegionDrawable(block.uiIcon);
            button.resizeImage(32f);
            button.setChecked(block == chosen);
            button.clicked(() -> {
                set.get(block);
                hide();
            });
            table.add(button).size(50f);

            if (++i >= cols) {
                i = 0;
                table.row();
            }
        }
    }

    public void show(Boolf<Block> filter, Cons<Block> set, Block chosen) {
        this.filter = filter;
        this.set = set;
        this.chosen = chosen;
        show();
    }
}
