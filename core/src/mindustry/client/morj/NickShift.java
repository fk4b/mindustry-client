package mindustry.client.morj;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.util.Strings;
import mindustry.gen.Call;

import java.nio.charset.StandardCharsets;

import static mindustry.Vars.net;
import static mindustry.Vars.player;

/**
 * Sends a server name command before a chat line.
 * The colored name has to fit in {@link mindustry.Vars#maxNameLength} bytes or the server cuts the tags.
 */
public final class NickShift{
    private NickShift(){}

    public static void beforeMessage(boolean command){
        if(command || player == null || !net.client()) return;
        if(!Core.settings.getBool("nickshift", false)) return;

        String plain = baseName();
        if(plain.isEmpty()) return;

        String colored = colorize(plain, Core.settings.getInt("nickshiftmode", 0));
        if(colored == null || colored.isEmpty() || colored.equals(player.name)) return;

        String prefix = Core.settings.getInt("nickshiftcmd", 0) == 1 ? "/name set " : "/name ";
        Call.sendChatMessage(prefix + colored);
    }

    private static String baseName(){
        String raw = Core.settings.getString("mynickshifter", "");
        if(raw == null || raw.isBlank()) raw = player.name;
        if(raw == null) return "";
        raw = Strings.stripColors(raw).replace("[", "").replace("]", "").replace("\n", "").replace("\t", "").trim();
        return raw;
    }

    private static String colorize(String plain, int mode){
        Color a = new Color();
        Color b = new Color();
        switch(mode){
            case 1 -> {
                readHex("nickcolor", "ffd37f", a);
                return fit(plain, new Color[]{a});
            }
            case 2 -> {
                bright(a);
                bright(b);
                return fit(plain, new Color[]{a, b});
            }
            case 3 -> {
                dim(a);
                dim(b);
                return fit(plain, new Color[]{a, b});
            }
            case 4 -> {
                harmony(a, b);
                return fit(plain, new Color[]{a, b});
            }
            case 5 -> {
                float hue = Mathf.random(360f);
                Color[] rain = new Color[4];
                for(int i = 0; i < rain.length; i++){
                    rain[i] = new Color().fromHsv((hue + i * 80f) % 360f, 0.85f, 1f).a(1f);
                }
                return fit(plain, rain);
            }
            case 6 -> {
                bright(a);
                return fit(plain, new Color[]{a});
            }
            case 7 -> {
                dim(a);
                return fit(plain, new Color[]{a});
            }
            default -> {
                readHex("nickgrad1", "ffd37f", a);
                readHex("nickgrad2", "ffffff", b);
                return fit(plain, new Color[]{a, b});
            }
        }
    }

    private static void bright(Color out){
        out.fromHsv(Mathf.random(360f), Mathf.random(0.75f, 1f), Mathf.random(0.9f, 1f)).a(1f);
    }

    private static void dim(Color out){
        out.fromHsv(Mathf.random(360f), Mathf.random(0.15f, 0.4f), Mathf.random(0.45f, 0.7f)).a(1f);
    }

    private static void harmony(Color a, Color b){
        float base = Mathf.random(360f);
        float shift = Mathf.randomBoolean() ? Mathf.random(40f, 80f) : Mathf.random(100f, 140f);
        if(Mathf.randomBoolean()) shift = -shift;
        a.fromHsv(base, Mathf.random(0.7f, 1f), Mathf.random(0.85f, 1f)).a(1f);
        b.fromHsv((base + shift + 360f) % 360f, Mathf.random(0.65f, 0.95f), Mathf.random(0.85f, 1f)).a(1f);
    }

    /** Uses as many color stops as fit. One stop trims the letters so the tag survives. */
    private static String fit(String plain, Color[] stops){
        int n = stops.length;
        while(n >= 1){
            String built = join(plain, stops, n);
            if(bytes(built) <= mindustry.Vars.maxNameLength) return built;
            n--;
        }
        String tag = tag(stops[0]);
        return tag + trimToBytes(plain, mindustry.Vars.maxNameLength - bytes(tag));
    }

    private static String join(String plain, Color[] stops, int n){
        int len = plain.length();
        StringBuilder sb = new StringBuilder(len + n * 9);
        int from = 0;
        for(int i = 0; i < n; i++){
            int to = (i == n - 1) ? len : len * (i + 1) / n;
            if(to <= from) continue;
            sb.append(tag(stops[i]));
            sb.append(plain, from, to);
            from = to;
        }
        if(sb.length() == 0) sb.append(tag(stops[0])).append(plain);
        return sb.toString();
    }

    private static String trimToBytes(String plain, int budget){
        if(budget <= 0) return "";
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for(int i = 0; i < plain.length(); ){
            int cp = plain.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int add = bytes(ch);
            if(used + add > budget) break;
            sb.append(ch);
            used += add;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static String tag(Color c){
        return "[#" + hex(c) + "]";
    }

    private static int bytes(String s){
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void readHex(String key, String def, Color out){
        String u = Core.settings.getString(key, def);
        if(u == null || u.isBlank()) u = def;
        u = u.trim();
        if(u.startsWith("#")) u = u.substring(1);
        try{
            out.set(Color.valueOf(u));
        }catch(Exception e){
            out.set(Color.valueOf(def));
        }
        out.a = 1f;
    }

    private static String hex(Color c){
        int r = Mathf.clamp((int)(c.r * 255f + 0.5f), 0, 255);
        int g = Mathf.clamp((int)(c.g * 255f + 0.5f), 0, 255);
        int b = Mathf.clamp((int)(c.b * 255f + 0.5f), 0, 255);
        return String.format("%02x%02x%02x", r, g, b);
    }
}
