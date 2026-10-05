package heritier.ntaganira.highbytes.wms.profile;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.profile
 * - File       : PhotoProcessorTest.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : An upload becomes a 256 px upright square JPEG, or is refused with a reason
 * </pre>
 */

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No database and no Spring: bytes in, bytes out. */
class PhotoProcessorTest {

    @Test
    void aPngBecomesASquareJpegOfTheStoredSize() throws IOException {
        byte[] out = PhotoProcessor.process(png(halves(600, 400, Color.RED, Color.BLUE)));

        assertThat(PhotoProcessor.formatOf(out)).isEqualTo("jpeg");
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(out));
        assertThat(stored.getWidth()).isEqualTo(PhotoProcessor.SIZE);
        assertThat(stored.getHeight()).isEqualTo(PhotoProcessor.SIZE);
        // The centre square of a landscape keeps both halves, side by side.
        assertThat(isRed(stored.getRGB(40, 128))).isTrue();
        assertThat(isBlue(stored.getRGB(216, 128))).isTrue();
    }

    @Test
    void aPhoneJpegTurnedOnItsSideIsStoredUpright() throws IOException {
        // Stored landscape, red left and blue right, tagged "turn a quarter
        // clockwise" as a phone held upright records it: seen upright, the
        // red half is on top.
        byte[] tagged = withOrientation(jpeg(halves(600, 400, Color.RED, Color.BLUE)), 6);
        assertThat(PhotoProcessor.exifOrientation(tagged)).isEqualTo(6);

        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(PhotoProcessor.process(tagged)));
        assertThat(isRed(stored.getRGB(200, 30))).as("top right is red").isTrue();
        assertThat(isBlue(stored.getRGB(60, 226))).as("bottom left is blue").isTrue();
    }

    @Test
    void nothingOfTheOriginalFileSurvives() throws IOException {
        byte[] tagged = withOrientation(jpeg(halves(600, 400, Color.RED, Color.BLUE)), 6);
        byte[] out = PhotoProcessor.process(tagged);
        assertThat(PhotoProcessor.exifOrientation(out)).as("no EXIF block").isEqualTo(1);
        assertThat(new String(out, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    @Test
    void theFilesOwnBytesDecideWhatItIsNotItsName() throws IOException {
        byte[] gif = gif(halves(300, 300, Color.RED, Color.BLUE));
        assertThatThrownBy(() -> PhotoProcessor.process(gif))
                .isInstanceOf(PhotoRejectedException.class).hasMessageContaining("JPEG or PNG");
        assertThatThrownBy(() -> PhotoProcessor.process("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PhotoRejectedException.class).hasMessageContaining("JPEG or PNG");
    }

    @Test
    void aFileThatOnlyLooksLikeAPhotoIsRefused() throws IOException {
        byte[] truncated = Arrays.copyOf(png(halves(300, 300, Color.RED, Color.BLUE)), 40);
        assertThatThrownBy(() -> PhotoProcessor.process(truncated))
                .isInstanceOf(PhotoRejectedException.class).hasMessageContaining("could not be read");
    }

    @Test
    void tooSmallTooLargeAndNothingAreRefused() throws IOException {
        assertThatThrownBy(() -> PhotoProcessor.process(png(halves(60, 60, Color.RED, Color.BLUE))))
                .isInstanceOf(PhotoRejectedException.class).hasMessageContaining("too small");
        byte[] huge = Arrays.copyOf(png(halves(300, 300, Color.RED, Color.BLUE)),
                (int) PhotoProcessor.MAX_UPLOAD_BYTES + 1);
        assertThatThrownBy(() -> PhotoProcessor.process(huge))
                .isInstanceOf(PhotoRejectedException.class).hasMessageContaining("over 5 MB");
        assertThatThrownBy(() -> PhotoProcessor.process(new byte[0]))
                .isInstanceOf(PhotoRejectedException.class).hasMessageContaining("Choose a photo");
    }

    @Test
    void transparencyLandsOnWhiteNotBlack() throws IOException {
        BufferedImage clear = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(PhotoProcessor.process(png(clear))));
        Color c = new Color(stored.getRGB(128, 128));
        assertThat(c.getRed() + c.getGreen() + c.getBlue()).isGreaterThan(700);
    }

    // ---- helpers ------------------------------------------------------------

    private static BufferedImage halves(int w, int h, Color left, Color right) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(left);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(right);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        return img;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        return write(img, "png");
    }

    private static byte[] jpeg(BufferedImage img) throws IOException {
        return write(img, "jpeg");
    }

    private static byte[] gif(BufferedImage img) throws IOException {
        return write(img, "gif");
    }

    private static byte[] write(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    /** The JPEG with an EXIF block, big-endian, holding only the orientation tag, right after its start. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,          // header, IFD0 at 8
                0x00, 0x01,                                              // one entry
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,          // 0x0112, SHORT, count 1
                0x00, (byte) orientation, 0x00, 0x00,                    // the value
                0x00, 0x00, 0x00, 0x00                                   // no next IFD
        };
        int length = 2 + 6 + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                                           // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static boolean isRed(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 200 && c.getGreen() < 60 && c.getBlue() < 60;
    }

    private static boolean isBlue(int rgb) {
        Color c = new Color(rgb);
        return c.getBlue() > 200 && c.getRed() < 60 && c.getGreen() < 60;
    }
}
