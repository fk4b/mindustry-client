package mindustry.client.morj.mk.fs;

import arc.scene.style.Drawable;
import arc.util.Log;
import arc.util.Nullable;
import lombok.AllArgsConstructor;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Date;

@AllArgsConstructor
public abstract class Entry {
    public String name;
    public @Nullable String comment;
    public @Nullable String icon;
    public final Date creationDate;
    public Date accessDate;
    public Date modifiedDate;
    public Directory parent;

    public abstract Drawable icon();
    public abstract void removed();

    public void write(DataOutput stream) throws IOException {
        stream.writeUTF(name);
        stream.writeBoolean(comment != null);
        if (comment != null) stream.writeUTF(comment);
        stream.writeBoolean(icon != null);
        if (icon != null) stream.writeUTF(icon);
        stream.writeLong(creationDate.getTime());
        stream.writeLong(accessDate.getTime());
        stream.writeLong(modifiedDate.getTime());
    }

    public void remove() {
        if (parent != null) {
            parent.contents.remove(name);
            parent = null;
        }
    }

    /**
     * Full path.
     * <p>
     * Always starts with a {@code /}. Panics if this entry is not attached to a root.
     */
    public String fullPath() {
        StringBuilder builder = new StringBuilder();
        Entry entry = this;
        builder.append(entry.name);
        while (entry.parent != null) {
            entry = entry.parent;
            // Log.info(entry.getClass().getSimpleName());
            builder.insert(0, "/");
            builder.insert(0, entry.name);
        }

        if (!(entry instanceof FileSystem)) throw new IllegalStateException("Attempting to obtain full path of a file not attached to a root.");

        return builder.toString();
    }
}
