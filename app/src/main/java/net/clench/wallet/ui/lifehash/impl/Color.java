// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

import static net.clench.wallet.ui.lifehash.impl.Utils.clamped;

public class Color {
    final double r;
    final double g;
    final double b;

    public Color(double r, double g, double b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }

    static Color fromUint8Values(int r, int g, int b) {
        return new Color((float) r / 255, (float) g / 255, (float) b / 255);
    }

    Color lighten(double t) {
        return this.lerpTo(Colors.WHITE, t);
    }

    Color darken(double t) {
        return this.lerpTo(Colors.BLACK, t);
    }

    double luminance() {
        return Math.sqrt(Math.pow(0.299 * this.r, 2) + Math.pow(0.587 * this.g, 2) + Math.pow(0.114 * this.b, 2));
    }

    Color burn(double t) {
        double f = Math.max(1.0f - t, 1.0e-7f);
        return new Color(
                Math.min(1.0f - (1.0f - this.r) / f, 1.0f),
                Math.min(1.0f - (1.0f - this.g) / f, 1.0f),
                Math.min(1.0f - (1.0f - this.b) / f, 1.0f)
                );
    }

    Color lerpTo(Color other, double t) {
        double f = clamped(t);
        double red = clamped(this.r * (1 - f) + other.r * f);
        double green = clamped(this.g * (1 - f) + other.g * f);
        double blue = clamped(this.b * (1 - f) + other.b * f);
        return new Color(red, green, blue);
    }
}
