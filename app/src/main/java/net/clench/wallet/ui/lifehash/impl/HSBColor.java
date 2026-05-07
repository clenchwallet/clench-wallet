// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

import static net.clench.wallet.ui.lifehash.impl.Utils.clamped;
import static net.clench.wallet.ui.lifehash.impl.Utils.modulo;

public class HSBColor {
    public double hue;
    public double saturation;
    public double brightness;

    public HSBColor(double hue) {
        this.hue = hue;
        this.saturation = 1;
        this.brightness = 1;
    }

    public HSBColor(Color color) {
        double r = color.r;
        double g = color.g;
        double b = color.b;

        double maxValue = Math.max(r, Math.max(g, b));
        double minValue = Math.min(r, Math.min(g, b));

        double brightness = maxValue;

        double d = maxValue - minValue;
        double saturation = maxValue == 0 ? 0 : d / maxValue;

        double hue;
        if (maxValue == minValue) {
            hue = 0;  // achromatic
        } else {
            if (maxValue == r) {
                hue = ((g - b) / d + (g < b ? 6 : 0)) / 6;
            } else if (maxValue == g) {
                hue = ((b - r) / d + 2) / 6;
            } else if (maxValue == b) {
                hue = ((r - g) / d + 4) / 6;
            } else {
                throw new IllegalArgumentException("Internal error.");
            }
        }
        this.hue = hue;
        this.saturation = saturation;
        this.brightness = brightness;
    }

    public Color color() {
        double v = clamped(brightness);
        double s = clamped(saturation);
        double red;
        double green;
        double blue;

        if(s <= 0) {
            red = v;
            green = v;
            blue = v;
        } else {
            double h = modulo(hue, 1);
            if(h < 0) {
                h += 1;
            }
            h *= 6;
            int i = (int) Math.floor(h);
            double f = h - i;
            double p = v * (1 - s);
            double q = v * (1 - s * f);
            double t = v * (1 - s * (1 - f));
            switch(i) {
                case 0:
                    red = v;
                    green = t;
                    blue = p;
                    break;
                case 1:
                    red = q;
                    green = v;
                    blue = p;
                    break;
                case 2:
                    red = p;
                    green = v;
                    blue = t;
                    break;
                case 3:
                    red = p;
                    green = q;
                    blue = v;
                    break;
                case 4:
                    red = t;
                    green = p;
                    blue = v;
                    break;
                case 5:
                    red = v;
                    green = p;
                    blue = q;
                    break;
                default:
                    throw new IllegalArgumentException();
            }
        }

        return new Color(red, green, blue);
    }
}
