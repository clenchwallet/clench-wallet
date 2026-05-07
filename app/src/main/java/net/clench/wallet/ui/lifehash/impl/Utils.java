// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

public class Utils {
    public static double lerpTo(double toA, double toB, double t) {
        return t * (toB - toA) + toA;
    }

    public static double lerpFrom(double fromA, double fromB, double t) {
        return (fromA - t) / (fromA - fromB);
    }

    public static double lerp(double fromA, double fromB, double toC, double toD, double t) {
        return lerpTo(toC, toD, lerpFrom(fromA, fromB, t));
    }

    // Return the minimum of `a`, `b`, and `c`.
    public static double min(double a, double b, double c) {
        return Math.min(Math.min(a, b), c);
    }

    public static double max(double a, double b, double c) {
        return Math.max(Math.max(a, b), c);
    }

    public static double clamped(double n) {
        return Math.max(Math.min(n, 1), 0);
    }

    public static double modulo(double dividend, double divisor) {
        return dividend % divisor; //Math.IEEEremainder(Math.IEEEremainder(dividend, divisor) + divisor, divisor);
    }

    private final static char[] hexArray = "0123456789abcdef".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static byte[] hexToBytes(final String data) {
        return decodeHex(data.toCharArray());
    }

    public static byte[] decodeHex(final char[] data) {

        final int len = data.length;

        if ((len & 0x01) != 0) {
            throw new IllegalArgumentException("Odd number of characters.");
        }

        final byte[] out = new byte[len >> 1];

        // two characters form the hex value.
        for (int i = 0, j = 0; j < len; i++) {
            int f = toDigit(data[j], j) << 4;
            j++;
            f = f | toDigit(data[j], j);
            j++;
            out[i] = (byte) (f & 0xFF);
        }

        return out;
    }

    protected static int toDigit(final char ch, final int index) {
        final int digit = Character.digit(ch, 16);
        if (digit == -1) {
            throw new IllegalArgumentException("Illegal hexadecimal character " + ch + " at index " + index);
        }
        return digit;
    }

    public static byte[] reverseBytes(byte[] bytes) {
        byte[] buf = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++)
            buf[i] = bytes[bytes.length - 1 - i];
        return buf;
    }
}