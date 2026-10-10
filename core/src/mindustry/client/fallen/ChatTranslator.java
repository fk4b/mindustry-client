package mindustry.client.fallen;

import arc.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Http.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.annotations.Annotations.*;
import mindustry.client.communication.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.ui.fragments.ChatFragment.*;

import java.nio.charset.*;
import java.util.regex.*;

/** Chat translation through the public Google Translate endpoint. No API key. */
public class ChatTranslator{
    public static final String[] languages = {
        "ru", "en", "uk", "be", "kk", "de", "fr", "es", "pt", "it", "pl", "cs",
        "tr", "zh-CN", "ja", "ko", "id", "vi", "th", "ar", "fa", "hi"
    };

    /** Tried in order. A 429 puts that host on a cooldown. */
    private static final String[] endpoints = {
        "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=",
        "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl=",
        "https://translate.google.com/translate_a/single?client=gtx&sl=auto&dt=t&tl="
    };
    private static final long[] cooldowns = new long[endpoints.length];
    private static long globalCooldown, lastErrorShown;
    /** The default Java user agent is rate-limited almost immediately. */
    private static final String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    public static boolean inEnabled(){ return Core.settings.getBool("chattrans-in", false); }
    public static boolean outEnabled(){ return Core.settings.getBool("chattrans-out", false); }
    public static String inLang(){ return Core.settings.getString("chattrans-in-lang", "ru"); }
    public static String outLang(){ return Core.settings.getString("chattrans-out-lang", "en"); }

    public static void toggleIn(){ Core.settings.put("chattrans-in", !inEnabled()); }
    public static void toggleOut(){ Core.settings.put("chattrans-out", !outEnabled()); }

    public static String label(boolean in){
        boolean on = in ? inEnabled() : outEnabled();
        return (on ? "[accent]" : "[gray]") + (in ? Iconc.download : Iconc.upload) + (in ? inLang() : outLang()).toUpperCase();
    }

    public static void showPicker(boolean in){
        BaseDialog dialog = new BaseDialog("@client.chattrans.title");
        dialog.cont.pane(t -> {
            int i = 0;
            for(String lang : languages){
                t.button(lang.toUpperCase(), Styles.flatTogglet, () -> {
                    Core.settings.put(in ? "chattrans-in-lang" : "chattrans-out-lang", lang);
                    dialog.hide();
                }).size(90f, 50f).pad(3f).checked(b -> lang.equals(in ? inLang() : outLang()));
                if(++i % 6 == 0) t.row();
            }
        });
        dialog.addCloseButton();
        dialog.show();
    }

    /** Callbacks run on the main thread. done receives the translation and the detected source language. */
    public static void translate(String text, String target, Cons2<String, String> done, Cons<Throwable> failed){
        if(Time.millis() < globalCooldown){
            failed.get(new RuntimeException("429, wait " + (globalCooldown - Time.millis()) / 1000 + "s"));
            return;
        }
        tryEndpoint(0, java.net.URLEncoder.encode(text, StandardCharsets.UTF_8), target, done, failed, null);
    }

    private static void tryEndpoint(int i, String q, String target, Cons2<String, String> done, Cons<Throwable> failed, Throwable last){
        while(i < endpoints.length && Time.millis() < cooldowns[i]) i++;
        if(i >= endpoints.length){
            globalCooldown = Time.millis() + 30_000;
            Throwable err = last != null ? last : new RuntimeException("429");
            Core.app.post(() -> failed.get(err));
            return;
        }
        int idx = i;
        Http.get(endpoints[idx] + target + "&q=" + q)
            .header("User-Agent", userAgent)
            .timeout(8000)
            .error(e -> {
                if(e instanceof HttpStatusException h && h.status == HttpStatus.TOO_MANY_REQUESTS){
                    cooldowns[idx] = Time.millis() + 60_000;
                }
                tryEndpoint(idx + 1, q, target, done, failed, shortError(e));
            })
            .submit(res -> {
                String[] parsed;
                try{
                    parsed = parse(res.getResultAsString());
                }catch(Throwable e){
                    tryEndpoint(idx + 1, q, target, done, failed, e);
                    return;
                }
                Core.app.post(() -> done.get(parsed[0], parsed[1]));
            });
    }

    /** Returns {translation, source language}. Accepts both gtx and dict-chrome-ex bodies. */
    private static String[] parse(String body){
        var arr = Jval.read(body).asArray();
        Jval first = arr.first();
        if(first.isString()) return new String[]{first.asString(), ""};
        var firstArr = first.asArray();
        if(firstArr.any() && firstArr.first().isString()){
            return new String[]{firstArr.first().asString(), firstArr.size > 1 && firstArr.get(1).isString() ? firstArr.get(1).asString() : ""};
        }
        StringBuilder sb = new StringBuilder();
        for(Jval seg : firstArr){
            if(!seg.isArray()) continue;
            Jval part = seg.asArray().first();
            if(part.isString()) sb.append(part.asString());
        }
        String detected = arr.size > 2 && arr.get(2).isString() ? arr.get(2).asString() : "";
        return new String[]{sb.toString(), detected};
    }

    private static Throwable shortError(Throwable e){
        if(e instanceof HttpStatusException h) return new RuntimeException(h.status.code + " " + h.status.name());
        return e;
    }

    private static boolean sameLang(String detected, String target){
        if(detected == null || detected.isEmpty()) return false;
        return detected.equalsIgnoreCase(target) || detected.split("-")[0].equalsIgnoreCase(target.split("-")[0]);
    }

    private static final ObjectSet<String> skipWords = ObjectSet.with(
        "gg", "wp", "gl", "hf", "ok", "okay", "xd", "lol", "lmao", "kek", "ty", "thx", "np", "afk", "brb", "k", "kk", "pls", "plz",
        "omg", "wtf", "ffs", "smh", "rofl", "haha", "hehe", "oof", "rip", "ez", "ezpz", "f", "ggwp", "gj", "omw", "nvm", "idk", "idc",
        "stfu", "tf", "yes", "no", "yep", "nope", "sure", "atm", "rn", "tbh", "ikr", "yw", "nm", "dc", "lag", "wtb", "wts", "inc",
        "rtv", "t5", "t4", "t3", "t2", "t1",
        "гг", "ок", "лол", "кек", "хд", "спс", "пж", "афк", "ку", "дд", "да", "нет", "неа", "угу", "окей", "нзч", "имба", "ор",
        "пзц", "пц", "збс", "капец", "жесть", "хай", "го", "скоро", "жду", "пока", "кк", "бб", "сяб", "пасиб", "всм", "норм", "нрм"
    );

    private static final Pattern urlPattern = Pattern.compile("https?://\\S+|www\\.\\S+");

    /** Strips colors, the client signature and links. Null when there is nothing worth translating. */
    public static @Nullable String cleanForTranslation(String text){
        String raw = Strings.stripColors(InvisibleCharCoder.INSTANCE.strip(text));
        raw = urlPattern.matcher(raw).replaceAll(" ").trim();
        if(raw.codePoints().filter(Character::isLetter).count() < 2) return null;

        boolean allSkip = true;
        for(String word : raw.toLowerCase().split("[^\\p{L}]+")){
            if(!word.isEmpty() && !skipWords.contains(word)){
                allSkip = false;
                break;
            }
        }
        return allSkip ? null : raw;
    }

    /** Letters that mean the Cyrillic text is not Russian. */
    private static final String nonRussianCyrillic = "іїєґўәғқңөұүһ";

    /** True when the alphabet already matches the target, so a request would be wasted. */
    public static boolean alreadyInTarget(String raw, String target){
        Character.UnicodeScript script = switch(target){
            case "ru" -> Character.UnicodeScript.CYRILLIC;
            case "ko" -> Character.UnicodeScript.HANGUL;
            case "th" -> Character.UnicodeScript.THAI;
            case "hi" -> Character.UnicodeScript.DEVANAGARI;
            case "zh-CN", "ja" -> Character.UnicodeScript.HAN;
            default -> null;
        };
        if(script == null) return false;

        boolean hasLetters = false, hasKana = false;
        for(String word : raw.toLowerCase().split("[^\\p{L}]+")){
            if(word.isEmpty() || skipWords.contains(word)) continue;
            for(int i = 0; i < word.length(); ){
                int cp = word.codePointAt(i);
                i += Character.charCount(cp);
                Character.UnicodeScript s = Character.UnicodeScript.of(cp);
                boolean kana = s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA;
                hasKana |= kana;
                hasLetters = true;

                if(s == script || (target.equals("ja") && kana)){
                    if(target.equals("ru") && nonRussianCyrillic.indexOf(cp) >= 0) return false;
                    continue;
                }
                return false;
            }
        }
        if(!hasLetters) return false;
        if(target.equals("ja")) return hasKana;
        if(target.equals("zh-CN")) return !hasKana;
        return true;
    }

    private static class Pending{
        final ChatMessage msg;
        final String raw;

        Pending(ChatMessage msg, String raw){
            this.msg = msg;
            this.raw = raw;
        }
    }

    private static final Seq<Pending> queue = new Seq<>();
    private static boolean flushScheduled;
    private static final float batchDelay = 1.2f;
    private static final int maxBatchChars = 1800;

    public static void translateIncoming(ChatMessage msg){
        if(!inEnabled() || msg == null || msg.message == null || msg.unformatted == null) return;
        String raw = cleanForTranslation(msg.unformatted);
        if(raw == null || alreadyInTarget(raw, inLang())) return;

        queue.add(new Pending(msg, raw));
        if(!flushScheduled){
            flushScheduled = true;
            Timer.schedule(() -> Core.app.post(ChatTranslator::flush), batchDelay);
        }
    }

    private static void flush(){
        flushScheduled = false;
        if(queue.isEmpty()) return;
        Seq<Pending> all = queue.copy();
        queue.clear();

        Seq<Pending> batch = new Seq<>();
        int len = 0;
        for(Pending p : all){
            int l = java.net.URLEncoder.encode(p.raw, StandardCharsets.UTF_8).length() + 3;
            if(batch.any() && len + l > maxBatchChars){
                sendBatch(batch);
                batch = new Seq<>();
                len = 0;
            }
            batch.add(p);
            len += l;
        }
        if(batch.any()) sendBatch(batch);
    }

    private static void sendBatch(Seq<Pending> batch){
        String target = inLang();
        if(batch.size == 1 || Time.millis() < cooldowns[0]){
            batch.each(p -> translateSingle(p, target));
            return;
        }

        StringBuilder url = new StringBuilder(endpoints[0]).append(target);
        for(Pending p : batch) url.append("&q=").append(java.net.URLEncoder.encode(p.raw, StandardCharsets.UTF_8));

        Http.get(url.toString())
            .header("User-Agent", userAgent)
            .timeout(8000)
            .error(e -> {
                if(e instanceof HttpStatusException h && h.status == HttpStatus.TOO_MANY_REQUESTS){
                    cooldowns[0] = Time.millis() + 60_000;
                }
                Core.app.post(() -> batch.each(p -> translateSingle(p, target)));
            })
            .submit(res -> {
                String[] results = new String[batch.size], langs = new String[batch.size];
                try{
                    var arr = Jval.read(res.getResultAsString()).asArray();
                    if(arr.size != batch.size) throw new RuntimeException("Batch size mismatch: " + arr.size + " != " + batch.size);
                    for(int i = 0; i < batch.size; i++){
                        Jval item = arr.get(i);
                        results[i] = item.isString() ? item.asString() : item.asArray().first().asString();
                        langs[i] = item.isArray() && item.asArray().size > 1 ? item.asArray().get(1).asString() : "";
                    }
                }catch(Throwable e){
                    Log.debug("Batch translation parse failed: @", e.getMessage());
                    Core.app.post(() -> batch.each(p -> translateSingle(p, target)));
                    return;
                }
                Core.app.post(() -> {
                    for(int i = 0; i < batch.size; i++) applyIncoming(batch.get(i), target, results[i], langs[i]);
                });
            });
    }

    private static void translateSingle(Pending p, String target){
        translate(p.raw, target, (result, detected) -> applyIncoming(p, target, result, detected),
            e -> Log.debug("Chat translation failed: @", e.getMessage()));
    }

    private static void applyIncoming(Pending p, String target, String result, String detected){
        if(sameLang(detected, target) || result == null || result.isEmpty() || result.equalsIgnoreCase(p.raw)) return;
        String line = "\n[lightgray]" + Iconc.chat + " " + result.replace("[", "[[");
        p.msg.message += line;
        p.msg.formattedMessage += line;
    }

    /** Translates an outgoing message, then passes it to send. On failure the original text is sent. */
    public static void translateOutgoing(String text, Cons<String> send){
        String target = outLang();
        translate(text, target, (result, detected) -> {
            send.get(sameLang(detected, target) || result == null || result.isEmpty() ? text : result);
        }, e -> {
            if(Time.timeSinceMillis(lastErrorShown) > 30_000){
                lastErrorShown = Time.millis();
                if(Vars.ui != null && Vars.ui.chatfrag != null){
                    Vars.ui.chatfrag.addMessage(Core.bundle.format("client.chattrans.failed", e.getMessage()));
                }
            }
            send.get(text);
        });
    }
}
