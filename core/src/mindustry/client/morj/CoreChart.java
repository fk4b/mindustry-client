package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import static mindustry.Vars.*;

/** 60 one-second samples of core item counts, drawn as one line per item. */
public final class CoreChart{
    private static final int samples = 60;
    private static final int maxLines = 6;
    private static int[][] history;
    private static int cursor, count;
    private static float acc;
    private static final IntSeq picked = new IntSeq();

    private CoreChart(){}

    static{
        Events.on(WorldLoadEvent.class, e -> reset());
    }

    public static boolean enabled(){
        return Core.settings.getBool("corechart", true);
    }

    public static void reset(){
        cursor = 0;
        count = 0;
        acc = 0f;
        history = null;
    }

    public static void update(){
        if(!enabled() || state.isMenu() || player == null) return;
        acc += Time.delta;
        if(acc < 60f) return;
        acc = 0f;
        CoreBuild core = player.team().core();
        if(core == null || core.items == null || content.items().isEmpty()) return;
        int n = content.items().size;
        if(history == null || history.length < n) history = new int[n][samples];
        for(Item item : content.items()){
            if(item.id < 0 || item.id >= history.length) continue;
            history[item.id][cursor] = core.items.get(item);
        }
        cursor = (cursor + 1) % samples;
        if(count < samples) count++;
    }

    public static Element widget(){
        return new Element(){
            @Override
            public void draw(){
                if(!enabled() || history == null || count < 2) return;
                Draw.color(Pal.darkestGray, 0.9f);
                Fill.rect(x + width / 2f, y + height / 2f, width, height);
                Draw.color(Pal.gray, 0.35f);
                Lines.stroke(1f);
                Lines.line(x + 2f, y + height / 2f, x + width - 2f, y + height / 2f);

                pick();
                float left = x + 4f;
                float right = x + width - 16f;
                float bottom = y + 4f;
                float top = y + height - 4f;
                for(int i = 0; i < picked.size; i++){
                    Item item = content.item(picked.get(i));
                    if(item == null) continue;
                    int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
                    for(int s = 0; s < count; s++){
                        int value = sample(item.id, s);
                        if(value < min) min = value;
                        if(value > max) max = value;
                    }
                    float span = Math.max(1, max - min);
                    Draw.color(item.color);
                    Lines.stroke(1.25f);
                    float prevX = 0f, prevY = 0f;
                    for(int s = 0; s < count; s++){
                        float px = left + (right - left) * (count == 1 ? 0f : s / (float)(count - 1));
                        float py = bottom + (top - bottom) * ((sample(item.id, s) - min) / span);
                        if(s > 0) Lines.line(prevX, prevY, px, py);
                        prevX = px;
                        prevY = py;
                    }
                    Draw.color();
                    Draw.rect(item.uiIcon, x + width - 8f, bottom + (top - bottom) * (i + 0.5f) / picked.size, 8f, 8f);
                }
                Draw.reset();
            }

            @Override
            public float getPrefHeight(){
                return 58f;
            }
        };
    }

    private static int sample(int item, int ageFromOldest){
        int start = (cursor - count + samples) % samples;
        int index = (start + ageFromOldest) % samples;
        return history[item][index];
    }

    private static void pick(){
        picked.clear();
        if(history == null) return;
        for(int pass = 0; pass < 2 && picked.size < maxLines; pass++){
            for(Item item : content.items()){
                if(picked.size >= maxLines) break;
                if(item.id < 0 || item.id >= history.length || picked.contains(item.id)) continue;
                int oldest = sample(item.id, 0);
                int newest = sample(item.id, count - 1);
                boolean changed = oldest != newest;
                boolean stock = newest > 0 || oldest > 0;
                if(pass == 0 ? changed : stock) picked.add(item.id);
            }
        }
    }
}
