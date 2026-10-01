package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.blocks.logic.*;

import java.io.*;
import java.util.zip.*;

import static mindustry.Vars.*;

/** Memory grid, live processor variables, and one-shot schematic paste. */
public final class LogicAssist{
    private LogicAssist(){}

    public static boolean enabled(){
        return Core.settings.getBool("logicassist", true);
    }

    public static void fillVars(Table table, LExecutor exec){
        table.clearChildren();
        table.top();
        if(exec == null || exec.vars == null) return;
        table.add("@variables").color(Pal.accent).growX().left().pad(4f).row();
        table.pane(pane -> {
            pane.top().left();
            int shown = 0;
            for(LVar var : exec.vars){
                if(var == null || var.constant) continue;
                if(++shown > 48){
                    pane.add("...").left().row();
                    break;
                }
                pane.add(var.name).color(Pal.accent).width(78f).ellipsis(true).left();
                Label value = pane.add("").growX().left().get();
                value.setAlignment(Align.left);
                final String[] prev = {null};
                value.update(() -> {
                    String text = var.isobj ? LExecutor.PrintI.toString(var.objval) : formatNum(var.numval, 2);
                    if(text.length() > 22) text = text.substring(0, 22);
                    if(!text.equals(prev[0])){
                        boolean flash = prev[0] != null;
                        prev[0] = text;
                        value.setText(text);
                        if(flash) value.setColor(Pal.accent);
                    }else{
                        value.color.lerp(Color.white, 0.08f);
                    }
                });
                pane.row();
            }
        }).grow().scrollX(false);
    }

    public static String formatNum(double value, int decimals){
        if(decimals <= 0 || Math.abs(value - Math.rint(value)) < 1e-6){
            return Long.toString(Math.round(value));
        }
        return Strings.fixed((float)value, Math.min(decimals, 8));
    }

    /** Reads processor source out of a schematic config. Does not touch the placed block. */
    public static String codeOf(byte[] data){
        if(data == null || data.length == 0) return null;
        try(DataInputStream stream = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))){
            stream.read();
            int length = stream.readInt();
            if(length <= 0 || length > 1024 * 512) return null;
            byte[] bytes = new byte[length];
            stream.readFully(bytes);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }catch(Exception ignored){
            return null;
        }
    }

    public static void pasteFromSchematic(LCanvas canvas){
        arc.struct.Seq<String> codes = new arc.struct.Seq<>();
        arc.struct.Seq<String> names = new arc.struct.Seq<>();
        for(Schematic schematic : schematics.all()){
            if(schematic.tiles == null) continue;
            for(Schematic.Stile stile : schematic.tiles){
                if(!(stile.block instanceof LogicBlock) || !(stile.config instanceof byte[] data)) continue;
                String code = codeOf(data);
                if(code == null || code.isEmpty()) continue;
                codes.add(code);
                names.add(schematic.tags == null ? "schematic" : schematic.name());
                break;
            }
            if(codes.size >= 40) break;
        }
        if(codes.isEmpty()){
            ui.showInfoToast(Core.bundle.get("client.logic.pastenone"), 2f);
            return;
        }
        if(codes.size == 1){
            load(canvas, codes.first(), names.first());
            return;
        }
        BaseDialog dialog = new BaseDialog("client.logic.pasteschem");
        dialog.cont.pane(list -> {
            for(int i = 0; i < codes.size; i++){
                int index = i;
                list.button(names.get(i), () -> {
                    load(canvas, codes.get(index), names.get(index));
                    dialog.hide();
                }).growX().minHeight(42f).pad(2f).row();
            }
        }).grow().maxHeight(480f);
        dialog.addCloseButton();
        dialog.show();
    }

    private static void load(LCanvas canvas, String code, String name){
        try{
            canvas.load(code);
            ui.showInfoToast(Core.bundle.format("client.logic.pasted", name), 1.4f);
        }catch(Throwable error){
            ui.showException(error);
        }
    }
}
