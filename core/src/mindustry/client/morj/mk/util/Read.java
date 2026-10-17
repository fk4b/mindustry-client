package mindustry.client.morj.mk.util;

public interface Read<E extends Exception> {
    int i() throws E;
    long l() throws E;
    float f() throws E;
    String sym() throws E;
    boolean nil() throws E;
}
