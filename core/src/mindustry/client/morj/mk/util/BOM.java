package mindustry.client.morj.mk.util;

import arc.func.Cons;
import arc.func.Cons2;
import arc.func.Cons3;
import arc.func.Func;
import arc.func.Func2;
import arc.func.Prov;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.io.Reads;
import arc.util.io.Writes;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import mindustry.client.morj.mk.Util;

public class BOM {
    public static final byte TYPE_NULL = 0;
    public static final byte TYPE_STRING = 1;
    public static final byte TYPE_BYTES = 2;
    public static final byte TYPE_I8 = 3;
    public static final byte TYPE_I16 = 4;
    public static final byte TYPE_I32 = 5;
    public static final byte TYPE_I64 = 6;
    public static final byte TYPE_ARRAY = 7;
    public static final byte TYPE_OBJECT = 8;

    @SerializerMetadata({})
    private static final Object EMPTY_METADATA_ELEMENT = null;
    public static final SerializerMetadata EMPTY_METADATA = Util.yeet(() -> BOM.class.getDeclaredField("EMPTY_METADATA_ELEMENT").getAnnotation(SerializerMetadata.class));

    private static final ObjectMap<Class<?>, Func<DeserializerContext, ?>> deserializers = new ObjectMap<>();
    private static final ObjectMap<Class<?>, Cons<SerializerContext<?>>> serializers = new ObjectMap<>();

    public static class SerializerException extends Exception {
        public SerializerException(Throwable cause) {
            super(cause);
        }
        public SerializerException(String message) {
            super(message);
        }
        public SerializerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @RequiredArgsConstructor
    public static class SerializerContext<T> {
        public Reads read;
        public T object;
        public SerializerMetadata annotation;
        public int annotationPosition = 0;

        public Class<?> nextMetaclass() {
            return annotation.value()[annotationPosition++];
        }

        public <Y> void with(Y value, Cons<SerializerContext<Y>> func) {
            @SuppressWarnings("unchecked")
            SerializerContext<Y> punned = (SerializerContext<Y>) this;
            T saved = object;
            punned.object = value;
            func.get(punned);
            object = saved;
        }
    }

    @RequiredArgsConstructor
    public static class DeserializerContext {
        public Reads read;
        public SerializerMetadata annotation;
        public int annotationPosition = 0;

        public Class<?> nextMetaclass() {
            return annotation.value()[annotationPosition++];
        }
    }

    public static <T> void registerDeserializer(Class<T> klass, Func<DeserializerContext, T> read) {
        deserializers.put(klass, read);
    }
    @SuppressWarnings("unchecked")
    public static <T> void registerSerializers(Class<T> klass, Cons<SerializerContext<T>> write) {
        serializers.put(klass, (Cons<SerializerContext<?>>) (Object) write);
    }

    public static <T> T deserialize(Class<T> klass, Reads read) {
        @SuppressWarnings("unchecked")
        final @Nullable Func<Reads, T> defined = (Func<Reads, T>) (Object) deserializers.get(klass);
        if (defined != null) return defined.get(read);
        throw new IllegalStateException("todo");
    }

    static {
        registerDeserializer(Seq.class, ctx -> {
            // if (ctx.read.i() != TYPE_ARRAY) throw new Illegal
            // int len = ctx.read.i();
            // len;
            throw new IllegalStateException("todo");
        });
    }
}
