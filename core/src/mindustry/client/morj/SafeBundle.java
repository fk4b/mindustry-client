package mindustry.client.morj;

import arc.*;
import arc.util.*;

import java.lang.reflect.*;
import java.util.*;

/** Arc keeps one MessageFormat for every bundle.format. Java 25 tears that object if two threads format at once. */
public final class SafeBundle{
    private SafeBundle(){}

    public static void install(){
        if(Core.bundle == null) return;
        try{
            Field field = I18NBundle.class.getDeclaredField("formatter");
            field.setAccessible(true);
            if(field.get(Core.bundle) instanceof LockedFormatter) return;
            field.set(Core.bundle, new LockedFormatter(Core.bundle.getLocale(), !I18NBundle.getSimpleFormatter()));
        }catch(Throwable t){
            Log.err("SafeBundle", t);
        }
    }

    static final class LockedFormatter extends TextFormatter{
        LockedFormatter(Locale locale, boolean messageFormat){
            super(locale, messageFormat);
        }

        @Override
        public synchronized String format(String pattern, Object... args){
            return super.format(pattern, args);
        }
    }
}
