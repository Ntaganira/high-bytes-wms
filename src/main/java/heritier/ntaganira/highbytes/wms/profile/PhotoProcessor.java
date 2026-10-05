package heritier.ntaganira.highbytes.wms.profile;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.profile
 * - File       : PhotoProcessor.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Turns an uploaded photo into the 256 px square JPEG that is stored, or says why it cannot
 * </pre>
 */

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * What is stored is never the file that was uploaded. The file is
 * recognised by its first bytes, not by its name or the type the browser
 * claims; decoded at no more resolution than the result needs; turned
 * upright as the camera recorded; cut to a square from the centre; and
 * written out as a new JPEG. Nothing of the original survives the trip,
 * the camera's metadata included: a phone photo records where it was taken.
 *
 * <p>Pure: bytes in, bytes out, no database and no Spring, so it is tested
 * on its own.
 */
final class PhotoProcessor {

    /** The side of the stored square, in pixels: twice the largest place it is shown. */
    static final int SIZE = 256;

    /** The largest upload read at all. */
    static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    /** Smaller than this is an icon, not a photo of anyone. */
    static final int MIN_SIDE = 96;

    /** A 12 MP phone photo is 12 million; this bounds what decoding may cost. */
    private static final long MAX_PIXELS = 40_000_000L;

    private static final float JPEG_QUALITY = 0.88f;

    private PhotoProcessor() {
    }

    /** The JPEG to store, or a {@link PhotoRejectedException} that says what to do instead. */
    static byte[] process(byte[] upload) {
        if (upload == null || upload.length == 0) {
            throw new PhotoRejectedException("Choose a photo to upload.");
        }
        if (upload.length > MAX_UPLOAD_BYTES) {
            throw new PhotoRejectedException("That photo is over 5 MB. Choose a smaller one.");
        }
        String format = formatOf(upload);
        if (format == null) {
            throw new PhotoRejectedException("Only a JPEG or PNG photo can be used.");
        }
        BufferedImage decoded = decode(upload, format);
        int orientation = "jpeg".equals(format) ? exifOrientation(upload) : 1;
        BufferedImage upright = orient(flatten(decoded), orientation);
        return encode(square(upright));
    }

    /** "jpeg" or "png" from the file's own signature, or null for anything else. */
    static String formatOf(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "png";
        }
        return null;
    }

    /**
     * Reads the size from the header before any pixel, refuses what is too
     * small or too large, then decodes every n-th pixel so the short side is
     * still at least twice the stored size.
     */
    private static BufferedImage decode(byte[] bytes, String format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) {
            throw new PhotoRejectedException("Only a JPEG or PNG photo can be used.");
        }
        ImageReader reader = readers.next();
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(in, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (Math.min(width, height) < MIN_SIDE) {
                throw new PhotoRejectedException(
                        "That image is too small for a photo. Use one at least " + MIN_SIDE + " pixels on each side.");
            }
            if ((long) width * height > MAX_PIXELS) {
                throw new PhotoRejectedException("That image has too many pixels. Use a photo under 40 megapixels.");
            }
            ImageReadParam param = reader.getDefaultReadParam();
            int step = Math.max(1, Math.min(width, height) / (SIZE * 2));
            param.setSourceSubsampling(step, step, 0, 0);
            BufferedImage image = reader.read(0, param);
            if (image == null) {
                throw new PhotoRejectedException("That file could not be read as a photo.");
            }
            return image;
        } catch (PhotoRejectedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // A truncated file, a CMYK JPEG, a PNG that lies about itself.
            throw new PhotoRejectedException("That file could not be read as a photo. Try saving it again as a JPEG.");
        } finally {
            reader.dispose();
        }
    }

    /** Onto white: a JPEG has no transparency, and a transparent corner would otherwise turn black. */
    private static BufferedImage flatten(BufferedImage src) {
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    /**
     * Upright as the camera recorded. A phone stores a portrait photo on its
     * side with a tag saying which way up it is; the browser obeys the tag in
     * its preview, so the stored photo must too, or it lies sideways.
     */
    static BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation < 2 || orientation > 8) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        // Each maps a source point (x, y) to where it is seen: m00, m10, m01, m11, m02, m12.
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // mirrored
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // upside down
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // mirrored, upside down
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // mirrored, on its side
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // turned a quarter clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // mirrored, on its other side
            default -> new AffineTransform(0, -1, 1, 0, 0, w);  // 8: a quarter anticlockwise
        };
        boolean sideways = orientation >= 5;
        BufferedImage out = new BufferedImage(sideways ? h : w, sideways ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** The centre square, brought to {@link #SIZE} in halving steps so it stays sharp. */
    private static BufferedImage square(BufferedImage src) {
        int side = Math.min(src.getWidth(), src.getHeight());
        BufferedImage current = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        while (current.getWidth() / 2 >= SIZE) {
            current = scaled(current, current.getWidth() / 2);
        }
        return current.getWidth() == SIZE ? current : scaled(current, SIZE);
    }

    private static BufferedImage scaled(BufferedImage src, int side) {
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, side, side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encode(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 * 1024);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the photo", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    // ---- EXIF orientation -------------------------------------------------

    /**
     * The orientation tag (0x0112) from a JPEG's EXIF block, 1 to 8, or 1
     * when there is none. Walks the segments up to the image data; any
     * malformed length ends the walk with "as stored".
     */
    static int exifOrientation(byte[] b) {
        int i = 2;
        while (i + 4 <= b.length && (b[i] & 0xFF) == 0xFF) {
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break;           // image data, or the end
            int length = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > b.length) break;
            if (marker == 0xE1 && length >= 8
                    && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f'
                    && b[i + 8] == 0 && b[i + 9] == 0) {
                return tiffOrientation(b, i + 10, i + 2 + length);
            }
            i += 2 + length;
        }
        return 1;
    }

    private static int tiffOrientation(byte[] b, int start, int end) {
        if (start + 8 > end) return 1;
        boolean little;
        if (b[start] == 'I' && b[start + 1] == 'I') little = true;
        else if (b[start] == 'M' && b[start + 1] == 'M') little = false;
        else return 1;
        long ifd = start + u32(b, start + 4, little);
        if (ifd < start || ifd + 2 > end) return 1;
        int entries = u16(b, (int) ifd, little);
        for (int k = 0; k < entries; k++) {
            long entry = ifd + 2 + 12L * k;
            if (entry + 12 > end) return 1;
            if (u16(b, (int) entry, little) == 0x0112) {
                int value = u16(b, (int) entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int at, boolean little) {
        int x = b[at] & 0xFF;
        int y = b[at + 1] & 0xFF;
        return little ? (y << 8) | x : (x << 8) | y;
    }

    private static long u32(byte[] b, int at, boolean little) {
        long value = 0;
        for (int k = 0; k < 4; k++) {
            int shift = little ? 8 * k : 8 * (3 - k);
            value |= (long) (b[at + k] & 0xFF) << shift;
        }
        return value;
    }
}
