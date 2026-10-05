package heritier.ntaganira.highbytes.wms.profile;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.profile
 * - File       : PhotoRejectedException.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : An upload that cannot become a profile photo, with a message the holder can act on
 * </pre>
 */

/** Why an upload cannot be a profile photo, in words shown to the person who chose it. */
public class PhotoRejectedException extends RuntimeException {

    public PhotoRejectedException(String message) {
        super(message);
    }
}
