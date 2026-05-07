// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

public class ChangeGrid extends Grid<Boolean> {
    public ChangeGrid(Size size) {
        super(size);
    }

    public void setChanged(Point point) {
        for(PointPair neighborhood : getNeighborhood(point)) {
            setValue(true, neighborhood.p());
        }
    }

    @Override
    protected Color colorForValue(Boolean value) {
        return value ? Colors.RED : Colors.BLUE;
    }

    @Override
    protected Boolean getDefault() {
        return Boolean.FALSE;
    }
}
