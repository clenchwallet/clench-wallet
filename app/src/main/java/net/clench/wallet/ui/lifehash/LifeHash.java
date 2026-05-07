// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash;

import net.clench.wallet.ui.lifehash.impl.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.clench.wallet.ui.lifehash.impl.Gradients.selectGradient;
import static net.clench.wallet.ui.lifehash.impl.Pattern.selectPattern;
import static net.clench.wallet.ui.lifehash.impl.Utils.clamped;
import static net.clench.wallet.ui.lifehash.impl.Utils.lerpFrom;

public class LifeHash {
    /**
     * Creates a LifeHash.Image object from the provided String
     *
     * @param s the String to extract UTF-8 bytes from as input
     * @param version the version of LifeHash to use
     * @param moduleSize the size of the LifeHash
     * @param hasAlpha whether transparency information should be included
     * @return an object representing the LifeHash
     */
    public static Image makeFromUTF8(String s, LifeHashVersion version, int moduleSize, boolean hasAlpha) {
        return makeFromData(s.getBytes(StandardCharsets.UTF_8), version, moduleSize, hasAlpha);
    }

    /**
     * Creates a LifeHash.Image object from the provided bytes
     *
     * @param data the bytes to use as input
     * @param version the version of LifeHash to use
     * @param moduleSize the size of the LifeHash
     * @param hasAlpha whether transparency information should be included
     * @return an object representing the LifeHash
     */
    public static Image makeFromData(byte[] data, LifeHashVersion version, int moduleSize, boolean hasAlpha) {
        byte[] digest = Sha256Hash.hash(data);
        return makeFromDigest(digest, version, moduleSize, hasAlpha);
    }

    /**
     * Creates a LifeHash.Image object from the provided SHA256 hash
     *
     * @param digest a 32 byte array representing a SHA256 hash to use as input
     * @param version the version of LifeHash to use
     * @param moduleSize the size of the LifeHash
     * @param hasAlpha whether transparency information should be included
     * @return an object representing the LifeHash
     */
    public static Image makeFromDigest(byte[] digest, LifeHashVersion version, int moduleSize, boolean hasAlpha) {
        if(digest.length != 32) {
            throw new IllegalArgumentException("Digest must be 32 bytes.");
        }

        int length;
        int maxGenerations;

        switch(version) {
            case VERSION1, VERSION2 -> {
                length = 16;
                maxGenerations = 150;
            }
            case DETAILED, FIDUCIAL, GRAYSCALE_FIDUCIAL -> {
                length = 32;
                maxGenerations = 300;
            }
            default -> throw new IllegalArgumentException("Invalid version.");
        }

        Size size = new Size(length, length);

        // These get reused from generation to generation by swapping them.
        CellGrid currentCellGrid = new CellGrid(size);
        CellGrid nextCellGrid = new CellGrid(size);
        ChangeGrid currentChangeGrid = new ChangeGrid(size);
        ChangeGrid nextChangeGrid = new ChangeGrid(size);

        Set<Sha256Hash> historySet = new HashSet<>();
        List<byte[]> history = new ArrayList<>();

        switch(version) {
            case VERSION1 -> nextCellGrid.setData(digest);
            case VERSION2 ->
                // Ensure that .version2 in no way resembles .version1
                    nextCellGrid.setData(Sha256Hash.hash(digest));
            case DETAILED, FIDUCIAL, GRAYSCALE_FIDUCIAL -> {
                byte[] digest1 = digest;
                // Ensure that grayscale fiducials in no way resemble the regular color fiducials
                if(version == LifeHashVersion.GRAYSCALE_FIDUCIAL) {
                    digest1 = Sha256Hash.hash(digest1);
                }
                byte[] digest2 = Sha256Hash.hash(digest1);
                byte[] digest3 = Sha256Hash.hash(digest2);
                byte[] digest4 = Sha256Hash.hash(digest3);
                byte[] digestFinal = new byte[digest1.length*4];
                System.arraycopy(digest1, 0, digestFinal, 0, digest1.length);
                System.arraycopy(digest2, 0, digestFinal, digest1.length, digest2.length);
                System.arraycopy(digest3, 0, digestFinal, digest1.length * 2, digest3.length);
                System.arraycopy(digest4, 0, digestFinal, digest1.length * 3, digest4.length);
                nextCellGrid.setData(digestFinal);
            }
        }

        nextChangeGrid.setAll(true);

        while (history.size() < maxGenerations) {
            CellGrid tempCellGrid = currentCellGrid;
            currentCellGrid = nextCellGrid;
            nextCellGrid = tempCellGrid;

            ChangeGrid tempChangeGrid = currentChangeGrid;
            currentChangeGrid = nextChangeGrid;
            nextChangeGrid = tempChangeGrid;

            byte[] data = currentCellGrid.getData();
            Sha256Hash hash = Sha256Hash.of(data);
            if (historySet.contains(hash)) {
                break;
            }
            historySet.add(hash);
            history.add(data);

            currentCellGrid.nextGeneration(currentChangeGrid, nextCellGrid, nextChangeGrid);
        }

        FracGrid fracGrid = new FracGrid(size);
        for (int i = 0; i < history.size(); i++) {
            currentCellGrid.setData(history.get(i));
            double frac = clamped(lerpFrom(0, history.size(), i + 1));
            fracGrid.overlay(currentCellGrid, frac);
        }

        // Normalizing the frac_grid to the range 0..1 was a step left out of .version1
        // In some cases it can cause the full range of the gradient to go unused.
        // This fixes the problem for the other versions, while remaining compatible
        // with .version1.
        if (version != LifeHashVersion.VERSION1) {
            double minValue = Double.MAX_VALUE;
            double maxValue = Double.MIN_VALUE;

            for (Point point : fracGrid.getPoints()) {
                double value = fracGrid.getValue(point);
                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);
            }

            for (Point point : fracGrid.getPoints()) {
                double currentValue = fracGrid.getValue(point);
                double value = lerpFrom(minValue, maxValue, currentValue);
                fracGrid.setValue(value, point);
            }
        }


        BitEnumerator entropy = new BitEnumerator(digest);

        switch(version) {
            case DETAILED ->
                // Throw away a bit of entropy to ensure we generate different colors and patterns from .version1
                    entropy.next();
            case VERSION2 ->
                // Throw away two bits of entropy to ensure we generate different colors and patterns from .version1 or .detailed.
                    entropy.nextUint2();
            default -> {
            }
        }

        ColorFunc gradient = selectGradient(entropy, version);
        Pattern pattern = selectPattern(entropy, version);
        ColorGrid color_grid = new ColorGrid(fracGrid, gradient, pattern);

        return makeImage(color_grid.size.width(), color_grid.size.height(), color_grid.colors(), moduleSize, hasAlpha);
    }

    static Image makeImage(int width, int height, List<Double> floatColors, int moduleSize, boolean hasAlpha) {
        if (moduleSize == 0) {
            throw new IllegalArgumentException("Invalid module size.");
        }

        int scaledWidth = width * moduleSize;
        int scaledHeight = height * moduleSize;
        int resultComponents = hasAlpha ? 4 : 3;
        int scaledCapacity = scaledWidth * scaledHeight * resultComponents;

        List<Byte> resultColors = new ArrayList<>(scaledCapacity);
        for(int i = 0; i < scaledCapacity; i++) {
            resultColors.add((byte)0);
        }

        for (int targetY = 0; targetY < scaledWidth; targetY++) {
            for (int targetX = 0; targetX < scaledHeight; targetX++) {
                int sourceX = targetX / moduleSize;
                int sourceY = targetY / moduleSize;
                int sourceOffset = (sourceY * width + sourceX) * 3;

                int targetOffset = (targetY * scaledWidth + targetX) * resultComponents;

                resultColors.set(targetOffset, (byte)(clamped(floatColors.get(sourceOffset)) * 255));
                resultColors.set(targetOffset + 1, (byte)(clamped(floatColors.get(sourceOffset + 1)) * 255));
                resultColors.set(targetOffset + 2, (byte)(clamped(floatColors.get(sourceOffset + 2)) * 255));
                if (hasAlpha) {
                    resultColors.set(targetOffset + 3, (byte)255);
                }
            }
        }

        return new Image(scaledWidth, scaledHeight, resultColors, hasAlpha);
    }

    public static class Image {
        public final int width;
        public final int height;
        public final List<Byte> colors;
        public final boolean hasAlpha;

        public Image(int width, int height, List<Byte> colors, boolean hasAlpha) {
            this.width = width;
            this.height = height;
            this.colors = colors;
            this.hasAlpha = hasAlpha;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public List<Byte> colors() {
            return colors;
        }

        public boolean hasAlpha() {
            return hasAlpha;
        }
    }
}
