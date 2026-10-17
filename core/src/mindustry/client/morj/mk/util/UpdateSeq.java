package mindustry.client.morj.mk.util;

import arc.func.Intc;
import arc.struct.Seq;
import arc.util.Nullable;

public class UpdateSeq<T> extends Seq<T> {
    public @Nullable Intc updated = null;

    @Override
    public UpdateSeq<T> add(T value) {
        super.add(value);
        if (updated != null) updated.get(size - 1);
        return this;
    }

    @Override
    public Seq<T> add(T value1, T value2) {
        super.add(value1, value2);
        if (updated != null) {
            updated.get(size - 2);
            updated.get(size - 1);
        }
        return this;
    }

    @Override
    public Seq<T> add(T value1, T value2, T value3) {
        super.add(value1, value2, value3);
        if (updated != null) {
            updated.get(size - 3);
            updated.get(size - 2);
            updated.get(size - 1);
        }
        return this;
    }
}
