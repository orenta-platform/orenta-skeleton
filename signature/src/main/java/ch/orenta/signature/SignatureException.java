package ch.orenta.signature;

/** A signature is missing, malformed, from an untrusted key, for another namespace or simply invalid. */
public final class SignatureException extends Exception {

    public SignatureException(String message) {
        super(message);
    }

    public SignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
