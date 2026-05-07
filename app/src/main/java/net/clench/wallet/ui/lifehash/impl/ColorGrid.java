// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ColorGrid extends Grid<Color> {
    static List<Transform> snowflakeTransforms = Arrays.asList(
            new Transform(false, false, false),
            new Transform(false, true, false),
            new Transform(false, false, true),
            new Transform(false, true, true)
    );

    static List<Transform> pinwheelTransforms = Arrays.asList(
            new Transform(false, false, false),
            new Transform(true, true, false),
            new Transform(true, false, true),
            new Transform(false, true, true)
    );

    static List<Transform> fiducialTransforms = Collections.singletonList(
            new Transform(false, false, false)
    );

    static Map<Pattern, List<Transform>> transformsMap = new HashMap<>();

    static {
        transformsMap.put(Pattern.SNOWFLAKE, snowflakeTransforms);
        transformsMap.put(Pattern.PINWHEEL, pinwheelTransforms);
        transformsMap.put(Pattern.FIDUCIAL, fiducialTransforms);
    }

    public ColorGrid(FracGrid fracGrid, ColorFunc gradient, Pattern pattern) {
        super(targetSize(fracGrid.getSize(), pattern));

        List<Transform> transforms = transformsMap.getOrDefault(pattern, new ArrayList<>());

        for(Point point : fracGrid.getPoints()) {
            double value = fracGrid.getValue(point);
            Color someColor = gradient.apply(value);
            draw(point, someColor, transforms);
        }
    }

    @Override
    protected Color colorForValue(Color color) {
        return color;
    }

    @Override
    protected Color getDefault() {
        return Colors.BLACK;
    }

    private static Size targetSize(Size inSize, Pattern pattern) {
        int multiplier = (pattern == Pattern.FIDUCIAL) ? 1 : 2;
        return new Size(inSize.width() * multiplier, inSize.height() * multiplier);
    }

    private Point transformPoint(Point point, Transform transform) {
        int x = point.x();
        int y = point.y();
        if (transform.transpose) {
            int temp = x;
            x = y;
            y = temp;
        }
        if (transform.reflectX) {
            x = maxX - x;
        }
        if (transform.reflectY) {
            y = maxY - y;
        }
        return new Point(x, y);
    }

    private void draw(Point p, Color color, List<Transform> transforms) {
        for (Transform t : transforms) {
            Point p2 = transformPoint(p, t);
            setValue(color, p2);
        }
    }

    static class Transform {
        final boolean transpose;
        final boolean reflectX;
        final boolean reflectY;

        Transform(boolean transpose, boolean reflectX, boolean reflectY) {
            this.transpose = transpose;
            this.reflectX = reflectX;
            this.reflectY = reflectY;
        }
    }
}
