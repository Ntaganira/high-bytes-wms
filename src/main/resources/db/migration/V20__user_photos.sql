-- =====================================================================
-- V20 — Profile photos
--
-- Each account may carry one photo, chosen by its holder on My profile.
-- A photo grants nothing: it is not access, it changes no right, and so it
-- restamps nobody and is not an access change. Its holder alone sets or
-- removes it; an administrator does not, which keeps the access office
-- away from what identifies a person on screen.
--
-- What is stored is never the file that was uploaded. The application
-- decodes it, turns it upright, cuts it to a square from the centre and
-- writes a fresh 256 px JPEG, so no camera metadata (a phone photo carries
-- where it was taken) and nothing hidden in the original survives. The
-- checks below hold the table to that: one JPEG per account, small.
--
-- The image lives in the database rather than on disk: it is a few
-- kilobytes, it is backed up with everything else, and there is no path
-- to get wrong. Replacing or removing it is recorded on the account's
-- audit trail; the trail records that it changed, never the image.
-- =====================================================================

CREATE TABLE user_photo (
    user_id      UUID PRIMARY KEY REFERENCES app_user(id),
    content      BYTEA NOT NULL,
    content_type VARCHAR(40) NOT NULL DEFAULT 'image/jpeg',
    sha256       CHAR(64) NOT NULL,
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A 256 px JPEG is tens of kilobytes; anything near the ceiling is not
    -- what the application writes.
    CONSTRAINT user_photo_size CHECK (octet_length(content) BETWEEN 1 AND 262144),
    CONSTRAINT user_photo_is_jpeg CHECK (content_type = 'image/jpeg'
                                         AND substring(content FROM 1 FOR 3) = '\xffd8ff'::bytea),
    CONSTRAINT user_photo_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE user_photo IS
  'The profile photo an account holder chose: a 256 px square JPEG the application re-encoded, never the uploaded file. Set and removed by the holder only, on the account''s audit trail. Grants nothing.';
