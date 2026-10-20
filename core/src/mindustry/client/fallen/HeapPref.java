package mindustry.client.fallen;

import arc.util.Log;
import arc.util.OS;
import mindustry.Vars;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

/**
 * The live heap cannot grow after the JVM has started.
 * The slider writes heapmb.txt; the next launch starts a new process with -Xmx.
 */
public final class HeapPref{
    private HeapPref(){}

    public static File file(){
        return new File(OS.getAppDataDirectoryString(Vars.appName), "heapmb.txt");
    }

    public static void save(int mb){
        try{
            File f = file();
            if(mb < 1024){
                if(f.isFile() && !f.delete()) Log.warn("Could not clear @", f);
                return;
            }
            File parent = f.getParentFile();
            if(parent != null && !parent.isDirectory() && !parent.mkdirs()){
                Log.warn("Could not create @", parent);
            }
            Files.writeString(f.toPath(), Integer.toString(mb), StandardCharsets.UTF_8);
        }catch(Exception e){
            Log.err(e);
        }
    }

    public static int read(){
        try{
            File f = file();
            if(!f.isFile()) return 0;
            String s = Files.readString(f.toPath(), StandardCharsets.UTF_8).trim();
            int v = Integer.parseInt(s);
            if(v < 1024 || v > 16384) return 0;
            return v;
        }catch(Exception e){
            return 0;
        }
    }

    /** Re-exec this jar when the saved heap is larger than the current one. No window yet. */
    public static void relaunchIfNeeded(String[] args){
        int want = read();
        if(want < 1024) return;
        if("1".equals(System.getProperty("morj.heapapplied"))) return;
        long haveMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        if(haveMb + 256 >= want) return;
        try{
            var src = HeapPref.class.getProtectionDomain().getCodeSource();
            if(src == null || src.getLocation() == null) return;
            File jar = new File(src.getLocation().toURI());
            if(!jar.isFile() || !jar.getName().toLowerCase().endsWith(".jar")) return;
            var cmd = ProcessHandle.current().info().command();
            if(cmd.isEmpty()) return;
            ArrayList<String> command = new ArrayList<>();
            command.add(cmd.get());
            command.add("-Xmx" + want + "m");
            command.add("-Dmorj.heapapplied=1");
            command.add("-jar");
            command.add(jar.getAbsolutePath());
            if(args != null){
                for(String a : args) command.add(a);
            }
            new ProcessBuilder(command).inheritIO().start();
            System.exit(0);
        }catch(Exception e){
            e.printStackTrace();
        }
    }
}
