// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

public class PointPair {
    private final Point o;
    private final Point p;

    public PointPair(Point o, Point p) {
        this.o = o;
        this.p = p;
    }

    public Point o() {
        return o;
    }

    public Point p() {
        return p;
    }
}
