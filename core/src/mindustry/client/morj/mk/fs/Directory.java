package mindustry.client.morj.mk.fs;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.scene.style.Drawable;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.gen.Icon;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;

public class Directory extends Entry {
    public Directory(String name, String comment, String icon, Date creationDate, Date accessDate, Date modifiedDate, Directory parent) {
        super(name, comment, icon, creationDate, accessDate, modifiedDate, parent);
    }

    @Override
    public void removed() {
        contents.each((ignored, x) -> x.removed());
    }

    protected ObjectMap<String, Entry> contents = new ObjectMap<>();

    public @Nullable Entry get(String path) {
        String[] segments = path.split("/");
        if (segments.length == 1 && segments[0].isEmpty()) return null;

        Entry start = this;
        if (segments[0].isEmpty()) while (start.parent != null) { start = start.parent; }

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];

            switch (segment) {
                case "":
                case ".":
                    continue;
                case "..":
                    if (start.parent != null) start = start.parent;
                    continue;
            }

            if (start instanceof Directory) {
                start = ((Directory) start).contents.get(segment);
                if (start == null) return null;
            } else return null;
        }

        return start;
    }

    @Override
    public Drawable icon() {
        if (icon != null) {
            return Core.atlas.drawable(icon);
        }
        return Icon.folder;
    }

    public static Directory read(DataInput stream) throws IOException {
        String name = stream.readUTF();
        String comment = stream.readBoolean() ? stream.readUTF() : null;
        String icon = stream.readBoolean() ? stream.readUTF() : null;
        Date creationDate = new Date(stream.readLong());
        Date accessDate = new Date(stream.readLong());
        Date modifiedDate = new Date(stream.readLong());

        int entriesCount = stream.readInt();
        ObjectMap<String, Entry> entries = new ObjectMap<>();

        Directory self = new Directory(name, comment, icon, creationDate, accessDate, modifiedDate, null);

        for (int i = 0; i < entriesCount; i++) {
            byte kind = stream.readByte();
            switch (kind) {
                case 0: {
                    File file = File.read(stream);
                    if (entries.put(file.name, file) != null) throw new IOException("Duplicate file name " + file.name);
                } break;
                case 1: {
                    Directory dir = Directory.read(stream);
                    if (entries.put(dir.name, dir) != null) throw new IOException("Duplicate file name " + dir.name);
                } break;
            }
        }

        self.contents = entries;
        entries.each((k, v) -> v.parent = self);
        return self;
    }

    @Override
    public void write(DataOutput stream) throws IOException {
        super.write(stream);

        stream.writeInt(contents.size);
        for (ObjectMap.Entry<String, Entry> x : contents) {
            if (x.value instanceof File) stream.writeByte(0);
            else if (x.value instanceof Directory) stream.writeByte(1);
            else throw new RuntimeException("Invalid subclass of 'Entry'");

            x.value.write(stream);
        }
    }

    public @Nullable Entry remove(String path) {
        Entry e = get(path);
        if (e == null) return null;
        e.remove();
        return e;
    }

    public @Nullable Directory mkdir(String path) {
        Log.info("Adding directory "+path);
        Seq<String> segments = new Seq<>(path.split("/"));
        if (segments.isEmpty()) {
            Log.warn("No segments on path!");
            return null;
        }
        String name = segments.pop();
        if (name.isEmpty()) {
            Log.warn("Name is empty!");
            return null;
        }

        Entry parent = segments.isEmpty() ? this : get(segments.toString("/"));
        if (!(parent instanceof Directory)) {
            Log.warn(parent == null ? "Nothing found!" : parent.name+" is not a directory");
            return null;
        }
        Directory dir = (Directory) parent;

        Date now = Date.from(Instant.now());

        Entry file = dir.get(name);
        if (file != null && !(file instanceof Directory)) {
            Log.warn("File already exists!");
            return null;
        }
        if (file != null) return (Directory) file;
        file = new Directory(name, null, null, now, now, now, dir);
        if (dir.contents.put(name, file) != null) throw new IllegalStateException("Unreachable!");

        return (Directory) file;
    }
    public @Nullable File add(String path, byte[] content) {
        remove(path);

        Log.info("Adding file "+path);
        Seq<String> segments = new Seq<>(path.split("/"));
        if (segments.isEmpty()) {
            Log.warn("No segments on path!");
            return null;
        }
        String name = segments.pop();
        if (name.isEmpty()) {
            Log.warn("Name is empty!");
            return null;
        }

        Entry parent = segments.isEmpty() ? this : get(segments.toString("/"));
        if (!(parent instanceof Directory)) {
            Log.warn(parent == null ? "Nothing found!" : parent.name+" is not a directory");
            return null;
        }
        Directory dir = (Directory) parent;

        Date now = Date.from(Instant.now());

        File file = new File(name, null, null, now, now, now, dir, content);
        if (dir.contents.put(name, file) != null) throw new IllegalStateException("remove() did not delete the file!");

        return file;
    }
    public @Nullable File add(Fi file) {
        return add(file.name(), file.readBytes());
    }

    public void each(Cons<Entry> func) {
        for (ObjectMap.Entry<String, Entry> x : contents) {
            func.get(x.value);
        }
    }
}
