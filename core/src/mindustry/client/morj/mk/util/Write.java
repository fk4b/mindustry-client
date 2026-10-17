package mindustry.client.morj.mk.util;

public interface Write<E extends Exception> {
    void i(int value) throws E;
    void l(long value) throws E;
    void f(float value) throws E;
    void sym(String value) throws E;
    void nil() throws E;
}
