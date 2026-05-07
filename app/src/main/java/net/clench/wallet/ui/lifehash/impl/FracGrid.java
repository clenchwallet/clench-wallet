// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

public class FracGrid extends Grid<Double> {
    public FracGrid(Size size) {
        super(size);
    }

    public void overlay(CellGrid cellGrid, double frac) {
        for(Point point : getPoints()) {
            if(cellGrid.getValue(point)) {
                setValue(frac, point);
            }
        }
    }

    @Override
    protected Color colorForValue(Double value) {
        return Colors.BLACK.lerpTo(Colors.WHITE, value);
    }

    @Override
    protected Double getDefault() {
        return 0d;
    }
}
