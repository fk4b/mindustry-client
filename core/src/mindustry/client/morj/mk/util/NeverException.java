package mindustry.client.morj.mk.util;

/** An exception that cannot be constructed, thus never be thrown. */
public class NeverException extends RuntimeException {
    private NeverException() {}
}
