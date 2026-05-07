// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

public class BitEnumerator {
    private final byte[] data;
    private int index;
    private int mask;

    public BitEnumerator(byte[] data) {
        this.data = data;
        this.index = 0;
        this.mask = 0x80;
    }

    public boolean hasNext() {
        return mask != 0 || index != (data.length - 1);
    }

    public boolean next() {
        if (!hasNext()) {
            throw new IllegalStateException("BitEnumerator underflow.");
        }

        if (mask == 0) {
            mask = 0x80;
            index += 1;
        }

        boolean b = (data[index] & mask) != 0;
        mask >>= 1;
        return b;
    }

    public int nextConfigurable(int bitMask, int bits) {
        int value = 0;
        for (int i = 0; i < bits; i++) {
            if (next()) {
                value |= bitMask;
            }
            bitMask >>= 1;
        }
        return value;
    }

    public int nextUint2() {
        int bitMask = 0x02;
        return nextConfigurable(bitMask, 2);
    }

    public int nextUint8() {
        int bitMask = 0x80;
        return nextConfigurable(bitMask, 8);
    }

    public int nextUint16() {
        int bitMask = 0x8000;
        return nextConfigurable(bitMask, 16);
    }

    public double nextFrac() {
        return (double) nextUint16() / 65535.0;
    }
}

