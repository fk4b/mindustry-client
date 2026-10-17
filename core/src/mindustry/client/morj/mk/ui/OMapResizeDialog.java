package mindustry.client.morj.mk.ui;

import arc.math.Mathf;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.util.Strings;
import mindustry.editor.MapResizeDialog;

import static mindustry.Vars.editor;

public class OMapResizeDialog extends MapResizeDialog {
    public static int minSize = 1;

    int width, height, shiftX, shiftY;

    boolean mulcheck(int a, int b) {
        try {
            Math.multiplyExact(a, b);
            return true;
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    public OMapResizeDialog(ResizeListener cons){
        super(cons);

        removeListener(getListeners().get(getListeners().size - 1));

        shown(() -> {
            cont.clear();
            width = editor.width();
            height = editor.height();

            Table table = new Table();

            for(boolean w : Mathf.booleans){
                table.add(w ? "@width" : "@height").padRight(8f);
                table.defaults().height(60f).padTop(8);

                table.field((w ? width : height) + "", TextField.TextFieldFilter.digitsOnly, value -> {
                    int val = Integer.parseInt(value);
                    if(w) width = val; else height = val;
                }).valid(value -> Strings.canParsePositiveInt(value) && Integer.parseInt(value) >= minSize &&
                        mulcheck(Integer.parseInt(value), w ? height : width));

                table.row();
            }

            for(boolean x : Mathf.booleans){
                table.add(x ? "@editor.shiftx" : "@editor.shifty").padRight(8f);
                table.defaults().height(60f).padTop(8);

                table.field("0", value -> {
                    int val = Integer.parseInt(value);
                    if(x) shiftX = val; else shiftY = val;
                }).valid(Strings::canParseInt);

                table.row();
            }

            cont.row();
            cont.add(table);

        });

        buttons.defaults().size(200f, 50f);
        buttons.button("@cancel", this::hide);
        buttons.button("@ok", () -> {
            cons.get(width, height, shiftX, shiftY);
            hide();
        });
    }
}
