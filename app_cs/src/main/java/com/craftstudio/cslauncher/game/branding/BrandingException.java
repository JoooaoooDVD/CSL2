package com.craftstudio.cslauncher.game.branding;

/** Failure with a user-facing reason code; the message is a developer detail for the logs. */
public class BrandingException extends Exception {
    public enum Reason {
        /** Game version below 1.21 or jar without the expected menu assets. */
        UNSUPPORTED_VERSION,
        /** The client jar has not been downloaded yet (first launch of this version). */
        JAR_MISSING,
        /** version.json / pack version information could not be read. */
        INVALID_METADATA,
        /** A bundled launcher asset is missing or unreadable (broken APK). */
        ASSET_MISSING,
        /** Writing the pack or options.txt failed (storage, permissions, disk full). */
        WRITE_FAILED,
        /** Post-install verification found the pack or the options entry missing. */
        VERIFY_FAILED
    }

    public final Reason reason;

    public BrandingException(Reason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    public BrandingException(Reason reason, String detail, Throwable cause) {
        super(detail, cause);
        this.reason = reason;
    }
}
