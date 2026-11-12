package mindustry.client.fallen;

import arc.*;
import arc.struct.*;

import static mindustry.Vars.*;

/**
 * Режим производительности: одним нажатием выключает тяжёлую графику игры и оверлеи клиента.
 * Прежние значения запоминаются и возвращаются при выключении.
 */
public class PerformanceMode{
    private static final String key = "perfmode", backup = "perfmode-backup-";

    /** Настройка, значение в режиме, значение по умолчанию (если её ни разу не трогали). */
    private static final Seq<Object[]> prefs = Seq.with(
        //игра
        new Object[]{"effects", false, true},
        new Object[]{"bloom", false, true},
        new Object[]{"atmosphere", false, true},
        new Object[]{"drawlight", false, true},
        new Object[]{"destroyedblocks", false, true},
        new Object[]{"animatedwater", false, true},
        new Object[]{"animatedshields", false, true},
        new Object[]{"skipcoreanimation", true, false},
        new Object[]{"hidedisplays", true, false},
        new Object[]{"screenshake", 0, 4},
        //оверлеи клиента
        new Object[]{"damagepopups", false, true},
        new Object[]{"transportscan", false, false},
        new Object[]{"corechart", false, true},
        new Object[]{"procstatus", false, true},
        new Object[]{"procref", false, true},
        new Object[]{"overdrivepreview", false, true},
        new Object[]{"powergrid", false, true},
        new Object[]{"ductcolor", false, false},
        new Object[]{"massdriverline", false, true}
    );

    public static boolean enabled(){
        return Core.settings.getBool(key, false);
    }

    public static void toggle(){
        set(!enabled());
    }

    public static void set(boolean on){
        if(on == enabled()) return;
        for(Object[] p : prefs){
            String name = (String)p[0];
            if(on){
                Core.settings.put(backup + name, Core.settings.has(name) ? Core.settings.get(name, p[2]) : p[2]);
                Core.settings.put(name, p[1]);
            }else{
                Object old = Core.settings.get(backup + name, p[2]);
                Core.settings.put(name, old);
                Core.settings.remove(backup + name);
            }
        }
        Core.settings.put(key, on);
        if(renderer != null) renderer.toggleBloom(Core.settings.getBool("bloom"));
        if(ui != null) ui.showInfoFade(Core.bundle.get(on ? "client.perf.on" : "client.perf.off"));
    }
}
