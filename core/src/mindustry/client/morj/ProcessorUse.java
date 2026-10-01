package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.ui.*;
import mindustry.world.blocks.logic.LogicBlock.*;

import static mindustry.Vars.*;

/** Hover a building to highlight processors that link it or read it with getblock/setblock. */
public final class ProcessorUse{
    private static final IntSeq poses = new IntSeq();
    private static final IntSeq lines = new IntSeq();
    private static int targetPos = -2;

    private ProcessorUse(){}

    public static boolean enabled(){
        return Core.settings.getBool("procref", true);
    }

    public static void update(){
        if(!enabled() || state.isMenu() || player == null || control.input == null){
            clear();
            return;
        }
        if(Core.scene == null || Core.scene.hasMouse() || Core.scene.hasDialog()){
            clear();
            return;
        }
        Building hovered = world.buildWorld(Core.input.mouseWorldX(), Core.input.mouseWorldY());
        int pos = hovered == null ? -1 : hovered.pos();
        if(pos == targetPos) return;
        targetPos = pos;
        poses.clear();
        lines.clear();
        if(hovered == null) return;

        int tx = hovered.tileX(), ty = hovered.tileY();
        int[] seen = {0};
        Groups.build.each(build -> {
            if(seen[0] > 700) return;
            if(!(build instanceof LogicBuild logic) || !logic.isValid()) return;
            seen[0]++;
            if(logic.team != player.team() && !logic.block.privileged) return;
            int line = codeLine(logic, tx, ty);
            boolean linked = false;
            if(logic.links != null){
                for(int i = 0; i < logic.links.size; i++){
                    LogicLink link = logic.links.get(i);
                    if(link != null && link.x == tx && link.y == ty){
                        linked = true;
                        break;
                    }
                }
            }
            if(line < 0 && !linked) return;
            if(poses.size >= 24) return;
            poses.add(logic.pos());
            lines.add(line);
        });
    }

    public static void draw(){
        if(!enabled() || poses.isEmpty() || player == null) return;
        Font font = Fonts.outline;
        boolean ints = font != null && font.usesIntegerPositions();
        if(font != null){
            font.setUseIntegerPositions(false);
            font.getData().setScale(0.38f / Math.max(renderer.camerascale, 0.05f));
            Draw.z(Layer.overlayUI);
        }
        for(int i = 0; i < poses.size; i++){
            Building build = world.build(poses.get(i));
            if(!(build instanceof LogicBuild logic) || !logic.isValid()) continue;
            Building hovered = targetPos < 0 ? null : world.build(targetPos);
            Drawf.square(logic.x, logic.y, logic.block.size * tilesize * 0.5f + 2f, Pal.logicOperations);
            if(hovered != null){
                Drawf.dashLine(Pal.logicOperations, hovered.x, hovered.y, logic.x, logic.y);
            }
            if(font == null) continue;
            int line = lines.get(i);
            String text = line >= 0 ? "L" + line : Core.bundle.get("client.morj.procref.link");
            font.setColor(Pal.logicOperations);
            font.draw(text, logic.x - 28f, logic.y + logic.block.size * tilesize * 0.5f + 8f, 56f, Align.center, false);
        }
        if(font != null){
            font.setColor(Color.white);
            font.getData().setScale(1f);
            font.setUseIntegerPositions(ints);
        }
        Draw.reset();
    }

    private static void clear(){
        targetPos = -2;
        poses.clear();
        lines.clear();
    }

    /** Assembler order is opcode, layer, result-or-block, x, y. */
    private static int codeLine(LogicBuild logic, int tx, int ty){
        String code = logic.code;
        if(code == null || code.isEmpty() || code.length() > 80_000) return -1;
        int line = 0;
        int start = 0;
        while(start <= code.length() && line <= LExecutor.maxInstructions){
            int end = code.indexOf('\n', start);
            if(end < 0) end = code.length();
            if(end > start && matches(logic.executor, code.substring(start, end), tx, ty)) return line;
            if(end >= code.length()) break;
            start = end + 1;
            line++;
        }
        return -1;
    }

    private static boolean matches(LExecutor exec, String row, int tx, int ty){
        row = row.trim();
        if(row.isEmpty() || row.charAt(0) == '#') return false;
        int space = row.indexOf(' ');
        if(space <= 0) return false;
        String op = row.substring(0, space);
        if(!op.equals("getblock") && !op.equals("setblock")) return false;
        String[] tok = row.split("\\s+");
        if(tok.length < 5) return false;
        return coord(exec, tok[3], tx) && coord(exec, tok[4], ty);
    }

    private static boolean coord(LExecutor exec, String token, int tile){
        if(token == null || token.isEmpty()) return false;
        try{
            return Math.round(Double.parseDouble(token)) == tile;
        }catch(NumberFormatException ignored){
            if(exec == null || exec.vars == null) return false;
            for(LVar var : exec.vars){
                if(var != null && token.equals(var.name) && !var.isobj && Math.round(var.numval) == tile) return true;
            }
            return false;
        }
    }
}
