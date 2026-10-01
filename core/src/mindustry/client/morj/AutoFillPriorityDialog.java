package mindustry.client.morj;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.graphics.g2d.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.client.utils.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/** Settings for auto transfer. Right click the panel button to open this. */
public class AutoFillPriorityDialog extends BaseDialog{
    private static AutoFillPriorityDialog instance;
    private final Table list = new Table();
    private Cell<ScrollPane> paneCell;

    public static void open(){
        if(instance == null) instance = new AutoFillPriorityDialog();
        instance.show();
    }

    @Override
    public Dialog show(){
        Dialog dialog = super.show();
        fit();
        return dialog;
    }

    private AutoFillPriorityDialog(){
        super("@client.autofill.title");
        addCloseButton();
        paneCell = cont.pane(list).grow();
        buttons.button("@client.autofill.reset", Icon.refresh, () -> {
            AutoTransfer.resetPriority();
            rebuild();
        });
        shown(() -> {
            rebuild();
            fit();
        });
        resized(this::fit);
    }

    /** Stage units match the window, so a fixed 520-wide pane stays a narrow strip. */
    private void fit(){
        float w = Mathf.clamp(Core.graphics.getWidth() * 0.62f, Math.min(680f, Core.graphics.getWidth() * 0.92f), 1100f);
        float h = Mathf.clamp(Core.graphics.getHeight() * 0.78f, Math.min(520f, Core.graphics.getHeight() * 0.86f), 980f);
        paneCell.size(w, h);
        pack();
    }

    /** Bundle lookup does not want the leading @ that labels use. */
    private static String text(String key){
        if(key != null && key.startsWith("@")) key = key.substring(1);
        return Core.bundle.get(key);
    }

    private void rebuild(){
        list.clear();
        list.top().left();
        list.defaults().growX().left();

        note(list, "@client.autofill.hint");

        section(list, "@client.autofill.source");
        Table toggles = new Table();
        toggles.left().defaults().left().padBottom(3f);
        check(toggles, "@client.autofill.enabled", AutoTransfer.enabled, on -> {
            AutoTransfer.enabled = on;
            Core.settings.put("autotransfer", on);
        });
        check(toggles, "@client.autofill.fromcores", AutoTransfer.Settings.getFromCores(), on -> {
            AutoTransfer.Settings.setFromCores(on);
            Core.settings.put("autotransfer-fromcores", on);
        });
        check(toggles, "@client.autofill.fromcontainers", AutoTransfer.Settings.getFromContainers(), on -> {
            AutoTransfer.Settings.setFromContainers(on);
            Core.settings.put("autotransfer-fromcontainers", on);
        });
        list.add(toggles).left().padBottom(4f).row();

        slider(list, "@client.autofill.mincore", 0f, 2000f, 10f,
            Core.settings.getInt("autotransfer-mincoreitems", 10),
            value -> {
                int amount = (int)value;
                AutoTransfer.Settings.setMinCoreItems(amount);
                Core.settings.put("autotransfer-mincoreitems", amount);
            },
            value -> String.valueOf(value.intValue()));
        note(list, "@client.autofill.mincore.note");

        float seconds = Mathf.clamp(AutoTransfer.Settings.getDelay() / 60f, 0f, 8f);
        slider(list, "@client.autofill.delay", 0f, 8f, 0.1f, seconds,
            value -> {
                float ticks = value * 60f;
                AutoTransfer.Settings.setDelay(ticks);
                Core.settings.put("autotransfer-transferdelay", ticks);
            },
            value -> Core.bundle.format("client.autofill.sec", Strings.fixed(value, 1)));
        note(list, "@client.autofill.delay.note");

        section(list, "@client.autofill.where");
        note(list, "@client.autofill.where.note");
        String[] cats = AutoTransfer.categorySnapshot();
        for(int i = 0; i < cats.length; i++){
            int index = i;
            String id = cats[i];
            list.table(Tex.button, row -> {
                row.left();
                TextureRegion icon = iconFor(id);
                if(icon != null) row.image(icon).size(32f).padLeft(8f).padRight(8f);
                CheckBox box = new CheckBox(Core.bundle.get("client.autofill.cat." + id));
                boolean on = AutoTransfer.categoryEnabled(id);
                box.setChecked(on);
                box.getLabel().setColor(on ? Color.white : Color.lightGray);
                box.changed(() -> {
                    AutoTransfer.setCategoryEnabled(id, box.isChecked());
                    box.getLabel().setColor(box.isChecked() ? Color.white : Color.lightGray);
                });
                row.add(box).growX().left().padRight(8f);
                row.button(Icon.upOpen, Styles.cleari, () -> moveCat(cats, index, -1)).size(40f).disabled(index == 0);
                row.button(Icon.downOpen, Styles.cleari, () -> moveCat(cats, index, 1)).size(40f).padRight(6f).disabled(index == cats.length - 1);
            }).growX().padBottom(3f).row();
        }

        section(list, "@client.autofill.what");
        note(list, "@client.autofill.what.note");
        Seq<Item> items = ordered();
        for(int i = 0; i < items.size; i++){
            int index = i;
            Item item = items.get(i);
            list.table(row -> {
                row.left();
                if(item.uiIcon != null) row.image(item.uiIcon).size(28f).padRight(8f);
                row.add(item.localizedName).growX().left();
                row.button(Icon.upOpen, Styles.cleari, () -> move(items, index, -1)).size(40f).disabled(index == 0);
                row.button(Icon.downOpen, Styles.cleari, () -> move(items, index, 1)).size(40f).disabled(index == items.size - 1);
            }).growX().padBottom(2f).row();
        }
    }

    private static void section(Table table, String key){
        table.add(text(key)).color(Pal.accent).left().padTop(14f).padBottom(2f).row();
        table.image().color(Pal.accent).height(2f).growX().padBottom(8f).row();
    }

    private static void note(Table table, String key){
        table.add(text(key)).color(Color.lightGray).growX().wrap().padBottom(8f).row();
    }

    private static void check(Table table, String key, boolean on, Boolc changed){
        CheckBox box = new CheckBox(text(key));
        box.setChecked(on);
        box.changed(() -> changed.get(box.isChecked()));
        table.add(box).left().padBottom(2f).row();
    }

    private static void slider(Table table, String name, float min, float max, float step, float current, Floatc changed, Func<Float, String> caption){
        table.add(text(name)).left().growX().wrap().padBottom(2f).row();
        table.table(row -> {
            row.left();
            Slider slider = new Slider(min, max, step, false);
            slider.setValue(Mathf.clamp(current, min, max));
            Label value = new Label(caption.get(slider.getValue()), Styles.outlineLabel);
            slider.changed(() -> {
                changed.get(slider.getValue());
                value.setText(caption.get(slider.getValue()));
            });
            row.add(slider).growX().minWidth(320f);
            row.add(value).width(84f).padLeft(12f).left();
        }).growX().padBottom(4f).row();
    }

    private static TextureRegion iconFor(String id){
        return switch(id){
            case "defense" -> Blocks.duo.uiIcon;
            case "factory" -> Blocks.siliconSmelter.uiIcon;
            case "units" -> Blocks.groundFactory.uiIcon;
            case "recon" -> Blocks.additiveReconstructor.uiIcon;
            default -> Blocks.container.uiIcon;
        };
    }

    private static Seq<Item> ordered(){
        Seq<Item> items = new Seq<>();
        ObjectSet<Item> seen = new ObjectSet<>();
        for(String name : AutoTransfer.prioritySnapshot()){
            Item item = content.item(name);
            if(item != null && seen.add(item)) items.add(item);
        }
        for(Item item : content.items()){
            if(item != null && !item.isHidden() && seen.add(item)) items.add(item);
        }
        return items;
    }

    private void move(Seq<Item> items, int index, int dir){
        int next = index + dir;
        if(next < 0 || next >= items.size) return;
        items.swap(index, next);
        String[] names = new String[items.size];
        for(int i = 0; i < items.size; i++) names[i] = items.get(i).name;
        AutoTransfer.savePriority(names);
        Core.app.post(this::rebuild);
    }

    private void moveCat(String[] cats, int index, int dir){
        int next = index + dir;
        if(next < 0 || next >= cats.length) return;
        String swap = cats[index];
        cats[index] = cats[next];
        cats[next] = swap;
        AutoTransfer.saveCategories(cats);
        Core.app.post(this::rebuild);
    }
}
