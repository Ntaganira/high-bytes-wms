package heritier.ntaganira.highbytes.wms.profile;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.profile
 * - File       : ProfilePhotoTest.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A holder sets, replaces and removes their own photo, each change on the account's trail
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Against a real PostgreSQL 16: the table holds what the application writes, and nothing else. */
class ProfilePhotoTest extends IntegrationTest {

    @Autowired ProfilePhotoService photos;
    @Autowired BranchService branches;
    @Autowired MockMvc mvc;

    UUID holder;
    BranchView kigali;

    @BeforeEach
    void signIn() {
        holder = fx.user("photo", "SALES");
        kigali = branches.findById(fx.kigali()).orElseThrow();
        fx.actAs(holder);
    }

    @Test
    void aHolderSetsReplacesAndRemovesTheirPhotoEachOnTheTrail() throws IOException {
        assertThat(photos.mine()).isEmpty();

        assertThat(photos.replaceMine(png(Color.RED), kigali)).isTrue();
        var first = photos.mine().orElseThrow();
        assertThat(PhotoProcessor.formatOf(first.content())).isEqualTo("jpeg");

        assertThat(photos.replaceMine(png(Color.BLUE), kigali)).isTrue();
        assertThat(photos.mine().orElseThrow().sha256()).isNotEqualTo(first.sha256());

        assertThat(photos.removeMine(kigali)).isTrue();
        assertThat(photos.mine()).isEmpty();
        assertThat(photos.removeMine(kigali)).as("nothing left to remove").isFalse();

        List<String> trail = jdbc.sql("""
                SELECT reason FROM audit_log
                 WHERE entity_name = 'app_user' AND entity_id = :id AND after_state::text LIKE '%Profile photo%'
                 ORDER BY id
                """).param("id", holder).query(String.class).list();
        assertThat(trail).containsExactly(
                "Profile photo added by the account holder.",
                "Profile photo replaced by the account holder.",
                "Profile photo removed by the account holder.");
    }

    @Test
    void theSamePhotoAgainChangesNothingAndRecordsNothing() throws IOException {
        photos.replaceMine(png(Color.RED), kigali);
        assertThat(photos.replaceMine(png(Color.RED), kigali)).isFalse();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM audit_log
                 WHERE entity_id = :id AND after_state::text LIKE '%Profile photo%'
                """).param("id", holder).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aRefusedUploadLeavesThePhotoAsItWas() throws IOException {
        photos.replaceMine(png(Color.RED), kigali);
        String before = photos.mine().orElseThrow().sha256();
        assertThatThrownBy(() -> photos.replaceMine("not a photo".getBytes(StandardCharsets.UTF_8), kigali))
                .isInstanceOf(PhotoRejectedException.class);
        assertThat(photos.mine().orElseThrow().sha256()).isEqualTo(before);
    }

    @Test
    void theTableTakesOnlyASmallJpeg() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO user_photo (user_id, content, sha256)
                VALUES (:id, convert_to('<svg/>', 'UTF8'), repeat('a', 64))
                """).param("id", holder).update())
                .hasMessageContaining("user_photo_is_jpeg");
    }

    @Test
    void eachHolderSeesOnlyTheirOwn() throws IOException {
        photos.replaceMine(png(Color.RED), kigali);
        fx.actAs(fx.user("other", "SALES"));
        assertThat(photos.mine()).isEmpty();
    }

    // ---- through the screens -------------------------------------------------

    @Test
    void theUploadArrivesThroughTheProfileAndShowsInTheTopbar() throws Exception {
        AppUserDetails me = userDetails.reload(holder, fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();

        mvc.perform(multipart("/profile/photo")
                        .file(new MockMultipartFile("photo", "me.png", "image/png", png(Color.RED)))
                        .with(user(me)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/profile"))
                .andExpect(flash().attribute("flashSuccess", containsString("Photo saved")));

        String version = jdbc.sql("SELECT left(sha256, 16) FROM user_photo WHERE user_id = :id")
                .param("id", holder).query(String.class).single();

        mvc.perform(get("/profile").with(user(me)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/profile/photo?v=" + version)))
                .andExpect(content().string(containsString("Change photo")));

        mvc.perform(get("/profile/photo").param("v", version).with(user(me)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Cache-Control", containsString("private")));
    }

    @Test
    void anUploadWithoutTheFormsTokenIsRefused() throws Exception {
        AppUserDetails me = userDetails.reload(holder, fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();

        mvc.perform(multipart("/profile/photo")
                        .file(new MockMultipartFile("photo", "me.png", "image/png", png(Color.RED)))
                        .with(user(me)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_photo WHERE user_id = :id")
                .param("id", holder).query(Long.class).single()).isZero();
    }

    @Test
    void aRefusalIsSaidOnTheProfileNotAsAnErrorPage() throws Exception {
        AppUserDetails me = userDetails.reload(holder, fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();

        mvc.perform(multipart("/profile/photo")
                        .file(new MockMultipartFile("photo", "me.png", "image/png", "GIF89a".getBytes(StandardCharsets.UTF_8)))
                        .with(user(me)).with(csrf()))
                .andExpect(redirectedUrl("/profile"))
                .andExpect(flash().attribute("flashError", containsString("JPEG or PNG")));
    }

    @Test
    void withoutAPhotoTheInitialsShowAndThereIsNoPhotoToFetch() throws Exception {
        AppUserDetails me = userDetails.reload(holder, fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();

        mvc.perform(get("/profile").with(user(me)))
                .andExpect(content().string(containsString("Add a photo")))
                .andExpect(content().string(not(containsString("/profile/photo?v="))));
        mvc.perform(get("/profile/photo").with(user(me)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/profile/photo/remove").with(user(me)).with(csrf()))
                .andExpect(redirectedUrl("/profile"));
    }

    private static byte[] png(Color colour) throws IOException {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, 320, 240);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
