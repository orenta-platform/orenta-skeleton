package ch.orenta.signature;

/** SSHSIG namespaces: one per purpose, so a signature is only valid for what it was made for (R45). */
public enum Namespace {
    /** Packages, including the start package. */
    PACKAGE("orenta-package"),
    /** Manifest of the infrastructure, read by the maintainer. */
    INFRA("orenta-infra"),
    /** Signed list of additional keys, with a version number. */
    KEYLIST("orenta-keylist");

    private final String value;

    Namespace(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
