package mindustry.client.morj.mk.util;

import arc.struct.Seq;
import arc.util.Nullable;

/** A sequential map of objects. */
public class IntSMap<V> {
    private final Ring<Segment<V>> segs = new Ring<>(Segment.class);

    private static final class Segment<V> {
        Ring<V> inner;
        int start;

        boolean isEmpty() {
            return inner.isEmpty();
        }

        boolean contains(int i, int softBounds) {
            return i - softBounds >= start && i < start + inner.size + softBounds;
        }

        @Nullable V put(int pos, V value) {
            if (pos < start) {
                while (start - 1 > pos) {
                    inner.addFirst(null);
                    start--;
                }
                inner.addFirst(value);
                start--;
                return null;
            } else if (pos - start > inner.size) {
                int times = pos - start - inner.size;
                while (times-- > 1) inner.addLast(null);
                inner.addLast(value);
                return null;
            } else {
                return inner.replace(pos - start, value);
            }
        }
    }

    public @Nullable V put(int pos, V value) {
        for (int i = 0; i < segs.size; i++) {
            Segment<V> seg = segs.get(i);
            if (seg.contains(pos, 5)) {
                @Nullable V old = seg.put(pos, value);

                return old;
            }
        }

        return null;
    }

    public void clear() {
        segs.clear();
    }
}
