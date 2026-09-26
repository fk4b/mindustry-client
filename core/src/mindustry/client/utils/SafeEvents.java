package mindustry.client.utils;

import arc.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;

import java.lang.reflect.*;

/**
 * Fires enum listeners one at a time. A mod that throws on {@code Trigger.update}
 * is logged once and skipped for the rest of the session. Failures inside the client still propagate.
 */
public final class SafeEvents{
    private static final Field eventsField;
    private static final Field consField;
    private static final IntSet logged = new IntSet();

    static{
        Field events = null;
        Field cons = null;
        try{
            events = Events.class.getDeclaredField("events");
            events.setAccessible(true);
            Class<?> handler = Class.forName("arc.Events$Handler");
            cons = handler.getDeclaredField("cons");
            cons.setAccessible(true);
        }catch(Throwable t){
            Log.err("SafeEvents reflection setup failed", t);
        }
        eventsField = events;
        consField = cons;
    }

    private SafeEvents(){
    }

    public static void fire(Enum<?> type){
        if(eventsField == null || consField == null){
            Events.fire(type);
            return;
        }

        Seq<?> listeners;
        try{
            // Raw map: ObjectMap.get is generic in the key, and a wildcard map rejects Enum.
            @SuppressWarnings("rawtypes")
            ObjectMap events = (ObjectMap)eventsField.get(null);
            listeners = (Seq<?>)events.get(type);
        }catch(Throwable t){
            Log.err("SafeEvents could not read listeners for @", type);
            Log.err(t);
            Events.fire(type);
            return;
        }
        if(listeners == null) return;

        Object[] items = listeners.items;
        for(int i = 0; i < listeners.size && i < items.length; i++){
            Object handler = items[i];
            if(handler == null) continue;

            Cons<Object> listener;
            try{
                listener = (Cons<Object>)consField.get(handler);
            }catch(Throwable t){
                Log.err("SafeEvents could not read a listener for @", type);
                Log.err(t);
                continue;
            }
            if(listener == null) continue;

            try{
                listener.get(type);
            }catch(Throwable t){
                if(t instanceof Error err) throw err;
                if(!fromMod(t)){
                    if(t instanceof RuntimeException re) throw re;
                    throw new RuntimeException(t);
                }
                if(logged.add(System.identityHashCode(handler))){
                    Log.err("Mod listener for @ failed. Later errors from this listener are ignored this session.", type);
                    Log.err(t);
                }
            }
        }
    }

    /** True when the throw site is outside the client and Arc. */
    private static boolean fromMod(Throwable t){
        for(StackTraceElement e : t.getStackTrace()){
            String name = e.getClassName();
            if(name.startsWith("mindustry.") || name.startsWith("arc.") || name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith("kotlin.") || name.startsWith("kotlinx.")) continue;
            return true;
        }
        return false;
    }
}
