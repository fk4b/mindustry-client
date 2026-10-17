package mindustry.client.morj.mk.fs;

import arc.struct.Seq;

public class Debug {
    private Debug() {}

    public static String print(FileSystem fs) {
        return printe(new StringBuilder(), fs, new Seq<>(Entry.class), 1).toString();
    }

    private static String spaceOf(int nesting) {
        StringBuilder b = new StringBuilder(nesting * 4);
        for (int i = 0; i < nesting; i++) b.append("    ");
        return b.toString();
    }

    private static StringBuilder printe(StringBuilder print, Entry entry, Seq<Entry> entries, int nesting) {
        int idx = entries.indexOf(entry);
        if (idx == -1) idx = entries.size;
        print.append(entry.getClass().getSimpleName()).append("#").append(idx);

        if (!entries.addUnique(entry)) return print;

        final String space = spaceOf(nesting);

        print.append(" {\n");

        if (!(entry instanceof FileSystem)) {
            print.append(space).append("    name: ").append(entry.name).append(",\n");
        }

        print.append(space).append("    parent: ");
        if (entry.parent == null) print.append("null,\n");
        else printe(print, entry.parent, entries, nesting + 1).append(",\n");

        print.append(space).append("    full-path: ").append(entry.fullPath()).append(",\n");

        if (entry instanceof Directory) {
            print.append(space).append("    contents: {\n");
            ((Directory) entry).contents.each((key, value) -> {
                printe(print.append(space).append("        ").append(key).append(": "), value, entries, nesting + 2).append(",\n");
            });
            print.append(space).append("    },\n");
        }
        else if (entry instanceof File) {
            print.append(space).append("    size: ").append(((File) entry).contents().length).append(",\n");
        }

        print.append(space).append("}");

        return print;
    }
}
