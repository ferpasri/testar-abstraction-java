package org.testar.abstraction;

/** Invalid rules, evidence scope, or unsuitable comparison values. */
public class AbstractionException extends IllegalArgumentException {
    public AbstractionException(String message) {
        super(message);
    }
}
