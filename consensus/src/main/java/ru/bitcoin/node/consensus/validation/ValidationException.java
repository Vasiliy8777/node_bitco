package ru.bitcoin.node.consensus.validation;

import ru.bitcoin.node.common.exception.BitcoinException;

public class ValidationException
        extends BitcoinException {

    public ValidationException(
            String message
    ) {
        super(message);
    }

    public ValidationException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
