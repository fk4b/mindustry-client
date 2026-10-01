package mindustry.client.morj;

import arc.*;
import arc.files.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.logic.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.util.*;

import static mindustry.Vars.*;

/** Autocomplete, find and replace, undo, and a local copy of processor code. Nothing is sent until the editor closes. */
public final class LogicEditor{
    private static final Seq<String> undo = new Seq<>();
    private static final Seq<String> redo = new Seq<>();
    private static final ObjectSet<String> keywords = ObjectSet.with(
        "set", "op", "jump", "end", "getlink", "sensor", "control", "radar", "unit", "ubind", "ucontrol", "ulocate", "uradar",
        "print", "printflush", "draw", "drawflush", "read", "write", "getblock", "setblock", "lookup", "packcolor", "unpackcolor",
        "wait", "stop", "noop", "format", "spawn", "status", "setrule", "message", "cutscene", "effect", "explosion", "setrate",
        "fetch", "sync", "getflag", "setflag", "setprop", "exec", "play", "idle", "stop", "bind", "approach", "pathfind",
        "autoPathfind", "target", "targetp", "itemDrop", "itemTake", "payDrop", "payTake", "payEnter", "flag", "mine", "build",
        "within", "always", "equal", "notEqual", "lessThan", "lessThanEq", "greaterThan", "greaterThanEq", "strictEqual",
        "add", "sub", "mul", "div", "idiv", "mod", "pow", "land", "shl", "shr", "or", "and", "not", "xor", "max", "min",
        "angle", "len", "noise", "abs", "log", "log10", "sin", "cos", "tan", "asin", "acos", "atan", "floor", "ceil", "sqrt", "rand"
    );
    private static final Seq<String> names = new Seq<>();
    private static String current = "";
    private static String opened = "";
    private static String namesFrom;
    private static boolean applying;
    private static float acc;
    private static TextField focused;

    private LogicEditor(){}

    public static void onOpen(LCanvas canvas){
        hideSuggest();
        undo.clear();
        redo.clear();
        applying = false;
        acc = 0f;
        current = opened = safe(canvas);
    }

    public static void onClose(LCanvas canvas, String tag){
        hideSuggest();
        if(!LogicAssist.enabled() || canvas == null) return;
        String now = safe(canvas);
        if(!now.equals(opened)) backup(now, tag);
    }

    public static void tick(LCanvas canvas){
        if(!LogicAssist.enabled() || applying || canvas == null || !ui.logic.isShown()) return;
        acc += Time.delta;
        if(acc < 20f) return;
        acc = 0f;
        String now = safe(canvas);
        if(now.equals(current)) return;
        if(undo.size >= 40) undo.remove(0);
        undo.add(current);
        redo.clear();
        current = now;
    }

    public static boolean undo(LCanvas canvas){
        if(!LogicAssist.enabled() || undo.isEmpty() || canvas == null) return false;
        redo.add(safe(canvas));
        return apply(canvas, undo.pop());
    }

    public static boolean redo(LCanvas canvas){
        if(!LogicAssist.enabled() || redo.isEmpty() || canvas == null) return false;
        undo.add(safe(canvas));
        return apply(canvas, redo.pop());
    }

    public static void watch(TextField field){
        if(field == null) return;
        field.addListener(new FocusListener(){
            @Override
            public void keyboardFocusChanged(FocusEvent event, Element actor, boolean focusedNow){
                if(focusedNow){
                    focused = field;
                    showSuggest(field);
                }else{
                    Core.app.post(() -> {
                        if(Core.scene.getKeyboardFocus() != field) hideSuggest();
                    });
                }
            }
        });
        field.changed(() -> {
            if(Core.scene.getKeyboardFocus() == field) showSuggest(field);
        });
    }

    public static void hideSuggest(){
        focused = null;
        if(ui == null || ui.logic == null || ui.logic.suggestBar == null) return;
        Table bar = ui.logic.suggestBar;
        bar.clearChildren();
        bar.visible = false;
        Cell<?> cell = ui.logic.getCell(bar);
        if(cell != null) cell.height(0f);
    }

    public static void openFind(LCanvas canvas){
        if(canvas == null) return;
        BaseDialog dialog = new BaseDialog("@client.logic.find");
        dialog.cont.defaults().pad(4f);
        TextField from = dialog.cont.field("", text -> {}).width(360f).get();
        from.setMessageText("@client.logic.findfrom");
        dialog.cont.row();
        TextField to = dialog.cont.field("", text -> {}).width(360f).get();
        to.setMessageText("@client.logic.findto");
        dialog.cont.row();
        dialog.buttons.button("@ok", () -> {
            int replaced = replace(canvas, from.getText(), to.getText());
            ui.showInfoToast(Core.bundle.format(replaced == 0 ? "client.logic.replacenone" : "client.logic.replaced", replaced), 1.4f);
            if(replaced > 0) dialog.hide();
        }).size(140f, 54f);
        dialog.addCloseButton();
        dialog.show();
        Core.app.post(from::requestKeyboard);
    }

    public static void backupNow(LCanvas canvas, String tag){
        backup(safe(canvas), tag);
        ui.showInfoToast(Core.bundle.get("client.logic.backupdone"), 1.2f);
    }

    public static void openBackups(LCanvas canvas){
        Fi dir = dataDirectory.child("logic-backups");
        if(!dir.exists()){
            ui.showInfoToast(Core.bundle.get("client.logic.backupnone"), 1.6f);
            return;
        }
        Fi[] files = dir.list();
        Seq<Fi> list = new Seq<>();
        for(Fi file : files){
            if(file != null && file.extEquals("txt")) list.add(file);
        }
        if(list.isEmpty()){
            ui.showInfoToast(Core.bundle.get("client.logic.backupnone"), 1.6f);
            return;
        }
        list.sort((a, b) -> b.name().compareTo(a.name()));
        if(list.size > 20) list.truncate(20);
        BaseDialog dialog = new BaseDialog("@client.logic.backups");
        dialog.cont.pane(pane -> {
            for(Fi file : list){
                pane.button(file.nameWithoutExtension(), () -> {
                    try{
                        remember(canvas);
                        apply(canvas, file.readString());
                        dialog.hide();
                    }catch(Throwable error){
                        ui.showException(error);
                    }
                }).growX().minHeight(36f).pad(2f).row();
            }
        }).grow().maxHeight(420f);
        dialog.addCloseButton();
        dialog.show();
    }

    private static void showSuggest(TextField field){
        if(!LogicAssist.enabled() || ui == null || ui.logic == null || ui.logic.suggestBar == null || field == null) return;
        String prefix = field.getText() == null ? "" : field.getText().trim();
        Table bar = ui.logic.suggestBar;
        bar.clearChildren();
        if(prefix.length() < 1 || isNumber(prefix)){
            hideBar(bar);
            return;
        }
        collect(ui.logic.canvas);
        bar.left();
        int shown = 0;
        String lower = prefix.toLowerCase(Locale.ROOT);
        for(String name : names){
            if(shown >= 8) break;
            if(name.equals(prefix) || !name.toLowerCase(Locale.ROOT).startsWith(lower)) continue;
            shown++;
            TextButton button = bar.button(name, Styles.cleart, () -> {
                field.setText(name);
                field.setCursorPosition(name.length());
                field.requestKeyboard();
            }).height(30f).minWidth(64f).maxWidth(150f).padRight(2f).get();
            button.getLabel().setEllipsis(true);
        }
        Cell<?> cell = ui.logic.getCell(bar);
        if(shown == 0){
            hideBar(bar);
            return;
        }
        bar.visible = true;
        if(cell != null) cell.height(34f);
        ui.logic.invalidate();
    }

    private static void hideBar(Table bar){
        bar.visible = false;
        Cell<?> cell = ui.logic.getCell(bar);
        if(cell != null) cell.height(0f);
    }

    private static void collect(LCanvas canvas){
        String code = safe(canvas);
        if(code.equals(namesFrom)) return;
        namesFrom = code;
        names.clear();
        ObjectSet<String> found = new ObjectSet<>();
        int i = 0;
        while(i < code.length()){
            char c = code.charAt(i);
            if(isNameChar(c)){
                int j = i + 1;
                while(j < code.length() && isNameChar(code.charAt(j))) j++;
                String token = code.substring(i, j);
                if(token.charAt(0) != '@' && !keywords.contains(token) && !isNumber(token)) found.add(token);
                i = j;
            }else{
                i++;
            }
        }
        for(String name : found) names.add(name);
        names.sort();
    }

    private static int replace(LCanvas canvas, String from, String to){
        if(from == null || from.isEmpty() || canvas == null) return 0;
        if(to == null) to = "";
        String now = safe(canvas);
        int[] count = {0};
        String next = from.indexOf(' ') >= 0 || from.indexOf('\n') >= 0
            ? replaceLiteral(now, from, to, count)
            : replaceTokens(now, from, to, count);
        if(count[0] <= 0 || next.equals(now)) return 0;
        remember(canvas);
        apply(canvas, next);
        return count[0];
    }

    private static void remember(LCanvas canvas){
        String now = safe(canvas);
        if(!now.equals(current)){
            if(undo.size >= 40) undo.remove(0);
            undo.add(current);
        }
        if(undo.size >= 40) undo.remove(0);
        undo.add(now);
        redo.clear();
    }

    private static boolean apply(LCanvas canvas, String code){
        applying = true;
        try{
            canvas.load(code);
            current = code;
            namesFrom = null;
            return true;
        }catch(Throwable error){
            ui.showException(error);
            return false;
        }finally{
            applying = false;
            hideSuggest();
        }
    }

    private static String replaceTokens(String code, String from, String to, int[] count){
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        while(i < code.length()){
            char c = code.charAt(i);
            if(isNameChar(c)){
                int j = i + 1;
                while(j < code.length() && isNameChar(code.charAt(j))) j++;
                String token = code.substring(i, j);
                if(token.equals(from)){
                    out.append(to);
                    count[0]++;
                }else{
                    out.append(token);
                }
                i = j;
            }else{
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String replaceLiteral(String code, String from, String to, int[] count){
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        while(i < code.length()){
            int at = code.indexOf(from, i);
            if(at < 0){
                out.append(code, i, code.length());
                break;
            }
            out.append(code, i, at).append(to);
            count[0]++;
            i = at + from.length();
        }
        return out.toString();
    }

    private static void backup(String code, String tag){
        if(code == null || code.isEmpty() || dataDirectory == null) return;
        try{
            Fi dir = dataDirectory.child("logic-backups");
            dir.mkdirs();
            String safeTag = tag == null ? "processor" : tag.replaceAll("[^\\p{L}\\p{N}_-]", "_");
            if(safeTag.isEmpty()) safeTag = "processor";
            if(safeTag.length() > 32) safeTag = safeTag.substring(0, 32);
            dir.child(System.currentTimeMillis() + "-" + safeTag + ".txt").writeString(code);
            Fi[] files = dir.list();
            Arrays.sort(files, Comparator.comparing(Fi::name));
            int extra = files.length - 20;
            for(int i = 0; i < extra; i++) files[i].delete();
        }catch(Throwable error){
            ui.showException(error);
        }
    }

    private static String safe(LCanvas canvas){
        if(canvas == null) return "";
        try{
            String code = canvas.save();
            return code == null ? "" : code;
        }catch(Throwable ignored){
            return current == null ? "" : current;
        }
    }

    private static boolean isNameChar(char c){
        return Character.isLetterOrDigit(c) || c == '_' || c == '@';
    }

    private static boolean isNumber(String token){
        if(token == null || token.isEmpty()) return false;
        int start = token.charAt(0) == '-' ? 1 : 0;
        if(start >= token.length()) return false;
        boolean dot = false;
        for(int i = start; i < token.length(); i++){
            char c = token.charAt(i);
            if(c == '.' && !dot){
                dot = true;
                continue;
            }
            if(!Character.isDigit(c)) return false;
        }
        return true;
    }
}
