package com.telemetry.desktop;

import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PNG export of any node without the javafx-swing bridge: the snapshot's pixels are copied into an
 * opaque AWT image and written with ImageIO.
 */
final class ImageExport {

    /** Rendered at 2× so exported images stay crisp when scaled down or printed. */
    private static final double SCALE = 2.0;

    private ImageExport() {
    }

    static void savePng(Node node, Path file) throws IOException {
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(SCALE, SCALE));
        WritableImage image = node.snapshot(params, null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        int[] argb = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        // Opaque RGB: the views have solid backgrounds, and alpha-channel PNGs make Word's PDF
        // export extremely slow when the screenshots are placed in a document.
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, argb, 0, w);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        ImageIO.write(out, "png", file.toFile());
    }
}
