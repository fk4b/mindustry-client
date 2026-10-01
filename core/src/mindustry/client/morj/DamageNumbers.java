package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

/** Floating damage and heal numbers. Local draw only. */
public final class DamageNumbers{
    private static final IntMap<Pop> pops = new IntMap<>();
    private static final IntSeq drop = new IntSeq();

    private DamageNumbers(){}

    public static void hit(Entityc entity, float amount, boolean heal, float maxHealth){
        if(headless || entity == null || amount <= 0.05f || Float.isNaN(amount)) return;
        if(!state.isGame()) return;
        if(!(entity instanceof Posc pos)) return;
        if(heal){
            if(!Core.settings.getBool("damagepopupsheal", false)) return;
        }else if(!Core.settings.getBool("damagepopups", true)){
            return;
        }

        boolean playerOnly = Core.settings.getBool("damagepopupsplayer", true);
        if(playerOnly){
            Unit mine = player == null ? null : player.unit();
            boolean mineHit = mine != null && mine.id() == entity.id();
            boolean enemy = false;
            if(!mineHit && entity instanceof Teamc tc && player != null && player.team() != null){
                Team team = tc.team();
                enemy = team != null && team != player.team();
            }
            if(!mineHit && !enemy) return;
        }else{
            int min = Core.settings.getInt("damagepopupsminhp", 600);
            if(min > 0 && maxHealth < min) return;
        }

        float camX = Core.camera.position.x, camY = Core.camera.position.y;
        if(Math.abs(pos.x() - camX) > Core.camera.width || Math.abs(pos.y() - camY) > Core.camera.height) return;

        Pop pop = pops.get(entity.id());
        if(pop != null && pop.heal == heal){
            pop.amount += amount;
            pop.x = pos.x();
            pop.y = pos.y();
            pop.age = Math.min(pop.age, 8f);
            return;
        }
        if(pops.size > 180) return;
        pop = new Pop();
        pop.heal = heal;
        pop.amount = amount;
        pop.x = pos.x();
        pop.y = pos.y();
        pops.put(entity.id(), pop);
    }

    public static void draw(){
        if(pops.isEmpty()) return;
        Font font = Fonts.outline;
        if(font == null) return;
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        float scale = 1.35f / Math.max(renderer.camerascale, 0.05f);
        font.getData().setScale(scale);
        Draw.z(Layer.overlayUI);

        for(IntMap.Entry<Pop> entry : pops){
            Pop pop = entry.value;
            pop.age += Time.delta;
            if(pop.age > 52f){
                drop.add(entry.key);
                continue;
            }
            float a = pop.age < 36f ? 1f : (52f - pop.age) / 16f;
            font.setColor(Tmp.c1.set(pop.heal ? Pal.heal : Pal.remove).a(a));
            String text = (pop.heal ? "+" : "-") + (pop.amount >= 10f ? Integer.toString(Math.round(pop.amount)) : Strings.fixed(pop.amount, 1));
            font.draw(text, pop.x - 40f, pop.y + 12f + pop.age * 0.35f, 80f, Align.center, false);
        }
        font.setColor(Color.white);
        font.getData().setScale(1f);
        font.setUseIntegerPositions(ints);

        for(int i = 0; i < drop.size; i++) pops.remove(drop.items[i]);
        drop.clear();
    }

    private static class Pop{
        float x, y, amount, age;
        boolean heal;
    }
}
