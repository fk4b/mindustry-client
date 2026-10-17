package mindustry.client.morj.mk.ui;

import arc.func.Boolf;
import arc.scene.ui.Dialog;
import arc.struct.Seq;
import arc.util.Reflect;
import arc.util.Structs;
import mindustry.client.morj.mk.Util;
import mindustry.client.morj.mk.util.Report;
import mindustry.Vars;
import mindustry.type.Item;
import mindustry.type.ItemSeq;
import mindustry.type.ItemStack;
import mindustry.ui.dialogs.LoadoutDialog;

public class OLoadoutDialog extends LoadoutDialog {
    private Runnable updater() {
        return Reflect.get(LoadoutDialog.class, this, "updater");
    }
    private Runnable resetter() {
        return Reflect.get(LoadoutDialog.class, this, "resetter");
    }

    public OLoadoutDialog() {
        super();

        getChildren().get(getChildren().size - 1).clicked(() -> {
            try {
                reseed();
                if (updater() != null) updater().run();
                Reflect.invoke(LoadoutDialog.class, this, "setup", Util.noargs);
            } catch (Throwable t) {
                Report.withException(t);
            }
        });
    }

    @Override
    public void show(int capacity, ItemSeq total, Seq<ItemStack> stacks, Boolf<Item> validator, Runnable reseter, Runnable updater, Runnable hider) {
        super.show(capacity, total, stacks, validator, reseter, updater, hider);
        reseed();
    }

    private void reseed() {
        Boolf<Item> validator = Reflect.get(LoadoutDialog.class, this, "validator");
        Seq<ItemStack> stacks = Reflect.get(LoadoutDialog.class, this, "stacks");

        stacks.addAll(
                Vars.content.items()
                        .select(i -> validator.get(i) && !stacks.contains(s -> s.item == i))
                        .map(i -> new ItemStack(i, 0))
        );
        stacks.sort(Structs.comparingInt(s -> s.item.id));
    }
}
