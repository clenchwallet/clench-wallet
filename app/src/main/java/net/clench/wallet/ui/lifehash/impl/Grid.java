// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public abstract class Grid<T> {
    // public
    public final Size size;

    // private
    protected final int capacity;
    protected final int maxX;
    protected final int maxY;

    protected final List<T> storage;

    private Supplier<T> supplier;

    public Grid(Size size) {
        this.size = size;
        this.capacity = size.width() * size.height();
        this.storage = new ArrayList<>(this.capacity);
        for(int i = 0; i < this.capacity; i++) {
            this.storage.add(getDefault());
        }
        this.maxX = size.width() - 1;
        this.maxY = size.height() - 1;
    }

    protected abstract Color colorForValue(T colorValue);

    protected abstract T getDefault();

    private int offset(Point point) {
        return point.y() * this.size.width() + point.x();
    }

    static int circularIndex(int index, int modulus) {
        return (index + modulus) % modulus;
    }

    public void setAll(T value) {
        for(int i = 0; i < this.capacity; i++) {
            this.storage.set(i, value);
        }
    }

    public void setValue(T value, Point point) {
        int offset = offset(point);
        this.storage.set(offset, value);
    }

    public T getValue(Point point) {
        return this.storage.get(offset(point));
    }

    public List<Point> getPoints() {
        List<Point> points = new ArrayList<>();
        for(int y = 0; y <= this.maxY; y++) {
            for(int x = 0; x <= this.maxX; x++) {
                points.add(new Point(x, y));
            }
        }
        return points;
    }

    public List<PointPair> getNeighborhood(Point point) {
        List<PointPair> pointPairs = new ArrayList<>();
        for(int oy = -1; oy <= 1; oy++) {
            for(int ox = -1; ox <= 1; ox++) {
                Point o = new Point(ox, oy);
                int px = circularIndex(ox + point.x(), this.size.width());
                int py = circularIndex(oy + point.y(), this.size.height());
                Point p = new Point(px, py);
                pointPairs.add(new PointPair(o, p));
            }
        }
        return pointPairs;
    }

    public List<Double> colors() {
        List<Double> result = new ArrayList<>();

        for(int idx = 0; idx < this.storage.size(); idx++) {
            T colorValue = this.storage.get(idx);
            Color color = this.colorForValue(colorValue);

            result.add(color.r);
            result.add(color.g);
            result.add(color.b);
        }

        return result;
    }

    public Size getSize() {
        return size;
    }

    protected void setChanged(Point p) {

    }
}

