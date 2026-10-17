package mindustry.client.morj.mk.util;

import arc.math.Mathf;

import java.util.Arrays;

// I do not remember how I got any of those combinations of variables, so you just gotta believe.

/** Ring array. */
public class Ring<T> {
    public T[] items;
    public int start = 0;
    public int size = 0;
    public Class<?> arrayClass = Object.class;

    public Ring() {}

    public Ring(Class<?> arrayClass) {
        this.arrayClass = arrayClass;
    }

    public T get(int i) {
        if (size == 0) throw new IndexOutOfBoundsException("Attempting to obtain an items from an empty 'Ring'");
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException("'i' (" + i + ") is not contained within this 'Ring' (0 ..= " + (size - 1) + ")");
        return items[(start + i) % items.length];
    }

    @SuppressWarnings("unchecked")
    public void addLast(T value) {
        if (!arrayClass.isInstance(value)) throw new IllegalArgumentException(value.getClass().getCanonicalName() + " is not a subclass of " + arrayClass.getCanonicalName());

        if (items == null || size == items.length) {
            int newSize = size;
            if (newSize < 4) newSize = 4;
            else newSize = Mathf.pow(2, Mathf.log2(newSize) + 1);

            T[] newArray = (T[]) java.lang.reflect.Array.newInstance(arrayClass, newSize);
            if (items != null) {
                if (start + size < items.length) {
                    System.arraycopy(items, start, newArray, 0, size);
                } else {
                    int breakpoint = items.length - start;
                    System.arraycopy(items, start, newArray, 0, breakpoint);
                    System.arraycopy(items, 0, newArray, breakpoint, size - breakpoint);
                }
            }

            items = newArray;
            start = 0;
        }

        items[(start + size++) % items.length] = value;
    }

    @SuppressWarnings("unchecked")
    public void addFirst(T value) {
        if (!arrayClass.isInstance(value)) throw new IllegalArgumentException(value.getClass().getCanonicalName() + " is not a subclass of " + arrayClass.getCanonicalName());

        if (items == null || size == items.length) {
            int newSize = size;
            if (newSize < 4) newSize = 4;
            else newSize = Mathf.pow(2, Mathf.log2(newSize) + 1);

            T[] newArray = (T[]) java.lang.reflect.Array.newInstance(arrayClass, newSize);
            if (items != null) {
                if (start + size < items.length) {
                    System.arraycopy(items, start, newArray, 0, size);
                } else {
                    int breakpoint = items.length - start;
                    System.arraycopy(items, start, newArray, 0, breakpoint);
                    System.arraycopy(items, 0, newArray, breakpoint, size - breakpoint);
                }
            }

            items = newArray;
            start = 0;
        }

        size++;
        if (--start < 0) start = items.length - 1;

        items[start] = value;
    }

    public T replace(int i, T value) {
        if (!arrayClass.isInstance(value)) throw new IllegalArgumentException(value.getClass().getCanonicalName() + " is not a subclass of " + arrayClass.getCanonicalName());
        if (size == 0) throw new IndexOutOfBoundsException("Attempting to replace an item in an empty 'Ring'");
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException("'i' (" + i + ") is not contained within this 'Ring' (0 ..= " + (size - 1) + ")");
        int pos = (start + i) % items.length;
        T item = items[pos];
        items[pos] = value;
        return item;
    }

    public T[] toArray() { return toArray(arrayClass); }
    @SuppressWarnings("unchecked")
    public T[] toArray(Class<?> itemType) {
        T[] array = (T[]) java.lang.reflect.Array.newInstance(itemType, size);

        if (items != null) {
            if (start + size < items.length) {
                System.arraycopy(items, start, array, 0, size);
            } else {
                int breakpoint = items.length - start;
                System.arraycopy(items, start, array, 0, breakpoint);
                System.arraycopy(items, 0, array, breakpoint, size - breakpoint);
            }
        }

        return array;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public T remove(int i) {
        if (size == 0) throw new IndexOutOfBoundsException("Attempting to remove an item from an empty 'Ring'");
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException("'i' (" + i + ") is not contained within this 'Ring' (0 ..= " + (size - 1) + ")");

        T old = get(i);

        if (start + size < items.length) {
            System.arraycopy(items, start + i + 1, items, start + i, size - 1);
            items[start + size - 1] = null;
            size--;
        } else {
            int breakpoint = items.length - start;

            if (i >= breakpoint) {
                int pos = i - breakpoint;
                System.arraycopy(items, pos + 1, items, pos, size - breakpoint - 1);
                items[size - breakpoint] = null;
                size--;
            } else if (i == start) {
                items[start] = null;
                if (++start >= items.length) start = 0;
                size--;
            } else {
                System.arraycopy(items, start, items, start + 1, i);
                items[start] = null;
                if (++start >= items.length) start = 0;
                size--;
            }
        }

        return old;
    }

    public void clear() {
        Arrays.fill(items, null);
        size = 0;
        start = 0;
    }

    @SuppressWarnings("unchecked")
    public static <T> Ring<T> concat(Ring<T> self, Ring<T> other) {
        Ring<T> ring = new Ring<>(self.arrayClass);
        ring.items = (T[]) java.lang.reflect.Array.newInstance(ring.arrayClass, self.size + other.size);

        if (self.start + self.size > self.items.length) {
            System.arraycopy(self.items, self.start, ring.items, 0, self.items.length - self.start);
            System.arraycopy(self.items, 0, ring.items, self.size - self.items.length + self.start, self.size - self.items.length + self.start);
        } else {
            System.arraycopy(self.items, self.start, ring.items, 0, self.size);
        }

        if (other.start + other.size > other.items.length) {
            System.arraycopy(other.items, other.start, ring.items, self.size, other.items.length - other.start);
            System.arraycopy(other.items, 0, ring.items, other.size - other.items.length + other.start + self.size, other.size - other.items.length + other.start);
        } else {
            System.arraycopy(other.items, other.start, ring.items, self.size, other.size);
        }

        return ring;
    }
}
