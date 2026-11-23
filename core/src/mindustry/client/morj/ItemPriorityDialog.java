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

/** Item priority for one block. */
public class ItemPriorityDialog extends BaseDialog{
    private static final ObjectMap<Block, ItemPriorityDialog> instances = new ObjectMap<>();
    private final Block block;
    private final Table list = new Table();
    private Cell<ScrollPane> paneCell;

    public static void open(Block block){
        ItemPriorityDialog dialog = instances.get(block);
        if(dialog == null){
            dialog = new ItemPriorityDialog(block);
            instances.put(block, dialog);
        }
        dialog.show();
    }

    private ItemPriorityDialog(Block block){
        super(Core.bundle.format("client.autofill.items.title", block.localizedName));
        this.block = block;
        addCloseButton();
        paneCell = cont.pane(list).grow();
        buttons.button("@client.autofill.items.reset", Icon.refresh, () -> {
            AutoTransfer.resetBlockItem(block);
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
        float w = Mathf.clamp(Core.graphics.getWidth() * 0.42f, Math.min(480f, Core.graphics.getWidth() * 0.9f), 720f);
        float h = Mathf.clamp(Core.graphics.getHeight() * 0.62f, Math.min(420f, Core.graphics.getHeight() * 0.82f), 760f);
        paneCell.size(w, h);
        pack();
    }

    private void rebuild(){
        list.clear();
        list.top().left();
        list.defaults().growX().left();

        note(list, "@client.autofill.items.note");
        Seq<Item> items = AutoTransfer.orderedAcceptedItems(block);
        if(items.isEmpty()){
            note(list, "@client.autofill.items.dynamic");
            return;
        }

        for(int i = 0; i < items.size; i++){
            int index = i;
            Item item = items.get(i);
            list.table(Tex.button, row -> {
                row.left();
                if(item.uiIcon != null) row.image(item.uiIcon).size(28f).padLeft(8f).padRight(8f);
                row.add(item.localizedName).growX().left();
                row.button(Icon.upOpen, Styles.cleari, () -> move(item, -1)).size(40f).disabled(index == 0);
                row.button(Icon.downOpen, Styles.cleari, () -> move(item, 1)).size(40f).padRight(6f).disabled(index == items.size - 1);
            }).growX().padBottom(2f).row();
        }
    }

    private void move(Item item, int dir){
        Seq<Item> items = AutoTransfer.orderedAcceptedItems(block);
        int index = items.indexOf(item);
        int next = index + dir;
        if(index < 0 || next < 0 || next >= items.size) return;
        items.swap(index, next);
        AutoTransfer.setItemPriority(block, items);
        Core.app.post(this::rebuild);
    }

    private static void note(Table table, String key){
        table.add(Core.bundle.get(key.substring(1))).color(Color.lightGray).growX().wrap().padBottom(8f).row();
    }
}
