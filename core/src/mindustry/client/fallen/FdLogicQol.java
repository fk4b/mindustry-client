package mindustry.client.fallen;

import arc.*;
import arc.func.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import mindustry.logic.*;
import mindustry.logic.LExecutor.*;
import mindustry.logic.LStatements.*;

/**
 * Logic Dialog QoL helpers. All features are gated by {@link #enabled()}.
 */
public final class FdLogicQol{
    public static final String SETTING = "fd-logic-qol";

    /** Snippet templates: name -> code with $var placeholders. */
    public static final Seq<Snippet> snippets = Seq.with(
        new Snippet("Unit control loop",
            "ubind $unit\n" +
            "sensor dead $unit @dead\n" +
            "jump 0 equal dead true\n" +
            "ucontrol approach $x $y 0 0 0\n" +
            "jump 0 always 0 0\n"),
        new Snippet("Core dead check",
            "sensor coreDead $core @dead\n" +
            "jump end equal coreDead true\n" +
            "end:\n" +
            "end\n"),
        new Snippet("Resource threshold check",
            "sensor amount $core $item\n" +
            "jump low lessThan amount $threshold\n" +
            "jump done always 0 0\n" +
            "low:\n" +
            "print \"low\"\n" +
            "printflush message1\n" +
            "done:\n" +
            "end\n"),
        new Snippet("Message every X ticks",
            "op add $timer $timer 1\n" +
            "jump skip lessThan $timer $interval\n" +
            "set $timer 0\n" +
            "print $text\n" +
            "printflush message1\n" +
            "skip:\n" +
            "end\n"),
        new Snippet("Simple radar + unit bind",
            "ubind $unit\n" +
            "uradar enemy any any distance 0 1 $result\n" +
            "sensor alive $result @dead\n" +
            "jump 0 equal alive true\n" +
            "sensor $tx $result @x\n" +
            "sensor $ty $result @y\n" +
            "ucontrol target $tx $ty 0 0 0\n" +
            "jump 0 always 0 0\n")
    );

    private FdLogicQol(){}

    public static boolean enabled(){
        return Core.settings.getBool(SETTING, false);
    }

    public static void init(){
        // Allow reading editor-only statements from text even if annotation processor did not register them.
        // Note: tokens is a reusable buffer; trailing entries may be stale — only read known slots carefully.
        LAssembler.customParsers.put("comment", tokens -> {
            CommentStatement c = new CommentStatement();
            if(tokens != null && tokens.length > 1 && tokens[1] != null && !tokens[1].isEmpty()){
                String t = tokens[1];
                if(t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"'){
                    t = t.substring(1, t.length() - 1);
                }
                // ignore stale buffer garbage that doesn't look like a comment payload
                if(!t.equals("jump") && !t.equals("set") && !t.equals("op")){
                    c.comment = t.replace("\\n", "\n");
                }
            }
            return c;
        });
        LAssembler.customParsers.put("label", tokens -> {
            LabelStatement l = new LabelStatement();
            if(tokens != null && tokens.length > 1 && tokens[1] != null && !tokens[1].isEmpty() && tokens[1].indexOf(' ') < 0){
                l.labelName = tokens[1];
            }
            return l;
        });
        LAssembler.customParsers.put("fdgroup", tokens -> {
            GroupStatement g = new GroupStatement();
            if(tokens != null && tokens.length > 1 && tokens[1] != null) g.groupName = tokens[1].replace('_', ' ');
            if(tokens != null && tokens.length > 2 && tokens[2] != null) g.collapsed = tokens[2].equals("1") || tokens[2].equals("true");
            if(tokens != null && tokens.length > 3 && tokens[3] != null){
                try{ g.innerCount = Integer.parseInt(tokens[3]); }catch(Exception ignored){}
            }
            return g;
        });
    }

    public static class LabelStatement extends LStatement{
        public String labelName = "label";

        @Override
        public void build(Table table){
            table.add("label").padLeft(4);
            table.field(labelName, v -> labelName = v).growX().pad(4);
        }

        @Override
        public LInstruction build(LAssembler builder){
            return null;
        }

        @Override
        public void write(StringBuilder builder){
            builder.append("label ").append(labelName);
        }
    }

    public static class GroupStatement extends LStatement{
        public String groupName = "group";
        public boolean collapsed;
        public int innerCount;

        @Override
        public void build(Table table){
            table.add("group").padLeft(4);
            table.field(groupName, v -> groupName = v).growX().pad(4);
        }

        @Override
        public LInstruction build(LAssembler builder){
            return null;
        }

        @Override
        public void write(StringBuilder builder){
            builder.append("fdgroup ").append(groupName.replace(' ', '_')).append(" ").append(collapsed ? "1" : "0").append(" ").append(innerCount);
        }
    }

    public static class Snippet{
        public final String name;
        public final String code;

        public Snippet(String name, String code){
            this.name = name;
            this.code = code;
        }

        /** Replace $name placeholders using a map (missing keys keep placeholder). */
        public String apply(ObjectMap<String, String> vars){
            String out = code;
            if(vars != null){
                for(var e : vars){
                    out = out.replace("$" + e.key, e.value);
                }
            }
            // default placeholders if still present
            out = out.replace("$unit", "unit")
                .replace("$x", "0").replace("$y", "0")
                .replace("$core", "core1")
                .replace("$item", "@copper")
                .replace("$threshold", "100")
                .replace("$timer", "timer")
                .replace("$interval", "60")
                .replace("$text", "\"hello\"")
                .replace("$result", "result")
                .replace("$tx", "tx").replace("$ty", "ty");
            return out;
        }

        public Seq<String> placeholders(){
            Seq<String> list = new Seq<>();
            int i = 0;
            while(i < code.length()){
                int at = code.indexOf('$', i);
                if(at < 0) break;
                int end = at + 1;
                while(end < code.length()){
                    char c = code.charAt(end);
                    if(!(Character.isLetterOrDigit(c) || c == '_')) break;
                    end++;
                }
                if(end > at + 1){
                    String name = code.substring(at + 1, end);
                    if(!list.contains(name)) list.add(name);
                }
                i = Math.max(end, at + 1);
            }
            return list;
        }
    }

    /** Collect variable names used in statement text (rough heuristic). */
    public static void collectUsedVars(LStatement st, ObjectSet<String> out){
        if(st == null) return;
        StringBuilder sb = new StringBuilder();
        st.saveUI();
        st.write(sb);
        String line = sb.toString();
        // tokenize roughly
        for(String tok : line.split("[\\s\"]+")){
            if(tok.isEmpty()) continue;
            char c0 = tok.charAt(0);
            if(c0 == '@' || c0 == '%' || Character.isDigit(c0) || c0 == '-' || c0 == '+') continue;
            if(tok.equals("jump") || tok.equals("set") || tok.equals("op") || tok.equals("true") || tok.equals("false")
                || tok.equals("always") || tok.equals("equal") || tok.equals("notEqual")) continue;
            // skip known opcodes / types
            if(Character.isLetter(c0) || c0 == '_'){
                out.add(tok);
            }
        }
    }
}
