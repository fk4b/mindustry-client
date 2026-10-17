package mindustry.client.morj.mk.util;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface SerializerMetadata {
    Class<?>[] value();
}
