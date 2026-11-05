package org.testar.abstraction;

/** A requested operation exceeds a processing limit. */
public final class AbstractionLimitException extends AbstractionException {
    public AbstractionLimitException(String message) {
        super(message);
    }
}
