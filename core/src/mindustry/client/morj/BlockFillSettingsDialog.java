package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.math.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.client.utils.*;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;

/** Per-category block fill settings. */
public class BlockFillSettingsDialog extends BaseDialog{
    private static final ObjectMap<String, BlockFillSettingsDialog> instances = new ObjectMap<>();
    private final String category;
    private final Table list = new Table();
    private Cell<ScrollPane> paneCell;

    public static void open(String category){
        BlockFillSettingsDialog dialog = instances.get(category);
        if(dialog == null){
            dialog = new BlockFillSettingsDialog(category);
            instances.put(category, dialog);
        }
        dialog.show();
    }

    private BlockFillSettingsDialog(String category){
        super(Core.bundle.get("client.autofill.cat." + category));
        this.category = category;
        addCloseButton();
        paneCell = cont.pane(list).grow();
        buttons.button("@client.autofill.reset.category", Icon.refresh, () -> {
            AutoTransfer.resetCategorySettings(category);
            rebuild();
        });
        shown(() -> {
            rebuild();
            fit();
        });
        resized(this::fit);
    }

    @Override
    public Dialog show(){
        Dialog dialog = super.show();
        fit();
        return dialog;
    }

    private void fit(){
        float w = Mathf.clamp(Core.graphics.getWidth() * 0.58f, Math.min(720f, Core.graphics.getWidth() * 0.92f), 1050f);
        float h = Mathf.clamp(Core.graphics.getHeight() * 0.72f, Math.min(520f, Core.graphics.getHeight() * 0.86f), 900f);
        paneCell.size(w, h);
        pack();
    }

    private void rebuild(){
        list.clear();
        list.top().left();
        list.defaults().growX().left();

        note(list, "@client.autofill.category.note");
        Seq<Block> blocks = AutoTransfer.blocksForCategory(category);
        if(blocks.isEmpty()){
            note(list, "@client.autofill.category.empty");
            return;
        }

        for(int i = 0; i < blocks.size; i++){
            int index = i;
            Block block = blocks.get(i);
            Seq<Item> accepted = AutoTransfer.acceptedItems(block);
            list.table(Tex.button, row -> {
                row.left();
                CheckBox box = new CheckBox(Core.bundle.get("client.autofill.fill"));
                box.setChecked(AutoTransfer.blockEnabled(block));
                box.changed(() -> AutoTransfer.setBlockEnabled(block, box.isChecked()));
                row.add(box).width(118f).left().padRight(6f);

                if(block.uiIcon != null) row.image(block.uiIcon).size(32f).padRight(8f);
                row.add(block.localizedName).growX().left().padRight(8f);

                Item item = AutoTransfer.preferredItem(block);
                if(item != null && item.uiIcon != null) row.image(item.uiIcon).size(24f).padRight(6f);
                row.add(item == null ? Core.bundle.get("client.autofill.item.auto") : item.localizedName)
                    .width(150f).left().padRight(6f);

                row.button(Icon.info, Styles.cleari, () -> ItemPriorityDialog.open(block)).size(40f).disabled(accepted.isEmpty());
                row.button(Icon.upOpen, Styles.cleari, () -> move(block, -1)).size(40f).disabled(index == 0);
                row.button(Icon.downOpen, Styles.cleari, () -> move(block, 1)).size(40f).padRight(6f).disabled(index == blocks.size - 1);
            }).growX().padBottom(3f).row();
        }
    }

    private void move(Block block, int dir){
        AutoTransfer.moveBlockInCategory(block, dir);
        Core.app.post(this::rebuild);
    }

    private static void note(Table table, String key){
        table.add(Core.bundle.get(key.substring(1))).color(Color.lightGray).growX().wrap().padBottom(8f).row();
    }
}
