package mindustry.client.antigrief;

import arc.struct.*;

/** Names a conveyor rotation when the builder unit is outside the viewer's sync range. */
public final class RotateAttribution{
    private static final IntMap<String> names = new IntMap<>();
    private static int missingId;
    private static String armedName;
    private static int armedId = -1;

    private RotateAttribution(){}

    /** Last unit id from a packet that this client does not have loaded. 0 clears it. */
    public static void noteMissing(int id){
        missingId = id;
    }

    public static int consumeMissing(){
        int id = missingId;
        missingId = 0;
        return id;
    }

    public static void remember(int id, String name){
        if(id != 0 && name != null && !name.isEmpty()) names.put(id, name);
    }

    public static String nameOf(int id){
        return id == 0 ? null : names.get(id);
    }

    public static void arm(String name, int id){
        armedName = name;
        armedId = id;
    }

    public static String armedName(){
        return armedName;
    }

    public static int armedId(){
        return armedId;
    }

    public static void disarm(){
        armedName = null;
        armedId = -1;
    }
}
