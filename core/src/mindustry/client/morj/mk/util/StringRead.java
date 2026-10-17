package mindustry.client.morj.mk.util;

import arc.util.Log;
import arc.util.Strings;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class StringRead implements Read<FormatException> {
    public final String source;
    private int seek = 0;

    @Override
    public int i() throws FormatException {
        if (seek >= source.length()) throw new FormatException("No more data is available");
        int length = source.indexOf(",", seek) - seek;
        if (length < 0) length = source.length() - seek;
        if (length == 0) throw new FormatException("Cannot interpret nil as an int");
        if (length == 1 && source.charAt(seek) == '0') {
            seek += 2;
            return 0;
        }
        int val = Strings.parseInt(source, 10, 0, seek, seek + length);
        if (val == 0) throw new FormatException("Not an integer");
        seek += length + 1;
        return val;
    }

    @Override
    public long l() throws FormatException {
        if (seek >= source.length()) throw new FormatException("No more data is available");
        int length = source.indexOf(",", seek) - seek;
        if (length < 0) length = source.length() - seek;
        if (length == 0) throw new FormatException("Cannot interpret nil as a long");
        if (length == 1 && source.charAt(seek) == '0') {
            seek += 2;
            return 0;
        }
        long val = Strings.parseLong(source, 10, seek, seek + length, 0);
        if (val == 0) throw new FormatException("Not an integer");
        seek += length + 1;
        return val;
    }

    @Override
    public float f() throws FormatException {
        if (seek >= source.length()) throw new FormatException("No more data is available");
        int length = source.indexOf(",", seek) - seek;
        if (length < 0) length = source.length() - seek;
        if (length == 0) throw new FormatException("Cannot interpret nil as a float");
        if (length == 1 && source.charAt(seek) == '0') return 0;
        String substr = source.substring(seek, seek + length);
        if (!Strings.canParseFloat(substr)) throw new FormatException("Not a float");
        seek += length + 1;
        return Strings.parseFloat(substr);
    }

    @Override
    public String sym() throws FormatException {
        if (seek >= source.length()) throw new FormatException("No more data is available");
        int length = source.indexOf(",", seek) - seek;
        if (length < 0) length = source.length() - seek;
        if (length == 0) throw new FormatException("Cannot interpret nil as a symbol");
        String s = source.substring(seek, seek + length);
        seek += length + 1;
        return s;
    }

    @Override
    public boolean nil() throws FormatException {
        boolean nil = seek >= source.length() || source.charAt(seek) == ',';
        if (nil) seek++;
        return nil;
    }
}
