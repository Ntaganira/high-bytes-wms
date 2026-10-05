package heritier.ntaganira.highbytes.wms.profile;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.profile
 * - File       : ProfilePhotoService.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The signed-in user's own profile photo: read, set or replace, and remove, audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Every method acts on the signed-in user's own photo and takes no user id,
 * so there is no id to tamper with: a holder sets, replaces and removes
 * their own, and nobody else's. An administrator has no way in here either;
 * a photo grants nothing, but it is what identifies a person on screen.
 *
 * <p>A change is recorded on the account's audit trail in its own
 * transaction, as part of the change: the photo and its record commit
 * together or not at all. The trail says that the photo changed and when,
 * never what it shows.
 */
@Service
public class ProfilePhotoService {

    /** The stored image and what identifies this version of it. */
    public record Photo(byte[] content, String sha256, Instant uploadedAt) {
        /** Short enough for a URL, long enough that two photos never share it. */
        public String version() {
            return sha256.substring(0, 16);
        }
    }

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss").withZone(KigaliTime.ZONE);

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ProfilePhotoService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @PreAuthorize("isAuthenticated()")
    public Optional<Photo> mine() {
        return photoOf(me().id());
    }

    /**
     * Sets or replaces the photo with what the upload becomes. False when it
     * comes out the same as the photo already there, so nothing changes and
     * nothing is recorded.
     *
     * @throws PhotoRejectedException when the upload cannot be a photo, saying why
     */
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public boolean replaceMine(byte[] upload, BranchView branch) {
        AppUserDetails me = me();
        byte[] jpeg = PhotoProcessor.process(upload);
        String sha = sha256(jpeg);

        Optional<Photo> before = photoOf(me.id());
        if (before.isPresent() && before.get().sha256().equals(sha)) {
            return false;
        }

        Instant uploadedAt = jdbc.sql("""
                INSERT INTO user_photo (user_id, content, content_type, sha256, uploaded_at)
                VALUES (:id, :content, 'image/jpeg', :sha, now())
                ON CONFLICT (user_id) DO UPDATE
                   SET content = EXCLUDED.content, sha256 = EXCLUDED.sha256, uploaded_at = EXCLUDED.uploaded_at
                RETURNING uploaded_at
                """)
                .param("id", me.id(), Types.OTHER)
                .param("content", jpeg)
                .param("sha", sha)
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).single();

        audit.recordInTransaction("app_user", me.id(), "User account · " + me.getUsername(), AuditAction.UPDATE,
                AuditSnapshot.of().field("Profile photo",
                        before.map(p -> "Uploaded " + WHEN.format(p.uploadedAt())).orElse("None"),
                        "Uploaded " + WHEN.format(uploadedAt)),
                branch, before.isPresent() ? "Profile photo replaced by the account holder."
                                           : "Profile photo added by the account holder.");
        return true;
    }

    /** Removes the photo. False when there was none, so nothing is recorded. */
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public boolean removeMine(BranchView branch) {
        AppUserDetails me = me();
        Optional<Instant> removed = jdbc.sql("DELETE FROM user_photo WHERE user_id = :id RETURNING uploaded_at")
                .param("id", me.id(), Types.OTHER)
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).optional();
        if (removed.isEmpty()) return false;

        audit.recordInTransaction("app_user", me.id(), "User account · " + me.getUsername(), AuditAction.UPDATE,
                AuditSnapshot.of().field("Profile photo", "Uploaded " + WHEN.format(removed.get()), "None"),
                branch, "Profile photo removed by the account holder.");
        return true;
    }

    private Optional<Photo> photoOf(UUID userId) {
        return jdbc.sql("SELECT content, sha256, uploaded_at FROM user_photo WHERE user_id = :id")
                .param("id", userId, Types.OTHER)
                .query((rs, n) -> new Photo(rs.getBytes("content"), rs.getString("sha256"),
                        rs.getTimestamp("uploaded_at").toInstant()))
                .optional();
    }

    private static AppUserDetails me() {
        return CurrentUser.get().orElseThrow(() -> new AccessDeniedException("Not signed in"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
