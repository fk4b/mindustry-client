package mindustry.client.morj.mk.util;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class StringBuilderWrite implements Write<NeverException> {
    public final StringBuilder builder;

    @Override
    public void i(int value) {
        builder.append(value).append(',');
    }

    @Override
    public void l(long value) {
        builder.append(value).append(',');
    }

    @Override
    public void f(float value) {
        builder.append(value).append(',');
    }

    @Override
    public void sym(String value) {
        builder.append(value).append(',');
    }

    @Override
    public void nil() {
        builder.append(',');
    }
}
