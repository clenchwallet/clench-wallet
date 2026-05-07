// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

public class CellGrid extends Grid<Boolean> {
    private final BitAggregator data;

    public CellGrid(Size size) {
        super(size);
        data = new BitAggregator();
    }

    public byte[] getData() {
        BitAggregator aggregator = new BitAggregator();

        for (Point point : getPoints()) {
            aggregator.append(getValue(point));
        }

        return aggregator.getData();
    }

    public void setData(byte[] data) {
        assert capacity == data.length * 8;

        BitEnumerator e = new BitEnumerator(data);
        int i = 0;

        while (e.hasNext()) {
            boolean value = e.next();
            storage.set(i, value);
            i += 1;
        }
    }

    static boolean isAliveInNextGeneration(boolean currentAlive, int neighborsCount) {
        if (currentAlive) {
            return neighborsCount == 2 || neighborsCount == 3;
        } else {
            return neighborsCount == 3;
        }
    }

    private int countNeighbors(Point point) {
        int total = 0;

        for (PointPair neighborhood : getNeighborhood(point)) {
            Point pointO = neighborhood.o();
            Point pointP = neighborhood.p();
            if (pointO.equals(Point.ZERO)) {
                continue;
            }

            if (getValue(pointP)) {
                total += 1;
            }
        }

        return total;
    }

    public void nextGeneration(ChangeGrid currentChangeGrid, CellGrid nextCellGrid, ChangeGrid nextChangeGrid) {
        nextCellGrid.setAll(false);
        nextChangeGrid.setAll(false);

        for (Point p : getPoints()) {
            boolean currentAlive = getValue(p);
            if (currentChangeGrid.getValue(p)) {
                int neighborsCount = countNeighbors(p);
                boolean nextAlive = isAliveInNextGeneration(currentAlive, neighborsCount);
                if (nextAlive) {
                    nextCellGrid.setValue(true, p);
                }
                if (currentAlive != nextAlive) {
                    nextChangeGrid.setChanged(p);
                }
            } else {
                nextCellGrid.setValue(currentAlive, p);
            }
        }
    }

    @Override
    protected Color colorForValue(Boolean colorValue) {
        if (colorValue) {
            return Colors.WHITE;
        } else {
            return Colors.BLACK;
        }
    }

    @Override
    protected Boolean getDefault() {
        return Boolean.FALSE;
    }
}

