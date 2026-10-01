package mindustry.client.morj;

import arc.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;

import static mindustry.Vars.*;

/** Remaining health of wave-team units already on the field. Local read only. */
public final class WaveHealth{
    private static final StringBuilder build = new StringBuilder();
    private static String cached = "";
    private static int count, acc = 999;

    private WaveHealth(){}

    public static boolean enabled(){
        return Core.settings.getBool("wavehp", true);
    }

    public static void update(){
        if(!enabled() || state.isMenu() || !state.rules.waves || state.rules.waveTeam == null){
            count = 0;
            cached = "";
            return;
        }
        if((acc += 1) < 12) return;
        acc = 0;

        Teams.TeamData data = state.rules.waveTeam.data();
        if(data == null || data.units == null){
            count = 0;
            cached = "";
            return;
        }

        float hp = 0f, max = 0f, shield = 0f;
        int n = 0;
        for(Unit unit : data.units){
            if(unit == null || unit.dead || !unit.isValid() || unit.isPlayer() || unit instanceof BlockUnitc) continue;
            float sh = Math.max(unit.shield(), 0f);
            if(unit.health <= 0f && sh <= 0f) continue;
            hp += Math.max(unit.health, 0f);
            max += Math.max(unit.maxHealth, 0f);
            shield += sh;
            n++;
        }
        count = n;
        if(n == 0){
            cached = "";
            return;
        }
        build.setLength(0);
        build.append(Core.bundle.format("client.morj.wavehp.line", UI.formatAmount(Math.round(hp)), UI.formatAmount(Math.round(max))));
        if(shield >= 1f){
            build.append(Core.bundle.format("client.morj.wavehp.shield", UI.formatAmount(Math.round(shield))));
        }
        cached = build.toString();
    }

    public static boolean active(){
        return enabled() && count > 0 && cached.length() > 0;
    }

    public static String line(){
        return cached;
    }
}
