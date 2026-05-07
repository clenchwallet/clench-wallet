// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

import java.util.List;

import static net.clench.wallet.ui.lifehash.impl.Utils.modulo;

public abstract class ColorFunc {
    public static ColorFunc reverse(ColorFunc c) {
        return new ColorFunc() {
            @Override
            Color apply(double value) {
                return c.apply(1 - value);
            }
        };
    }

    public static ColorFunc blend(Color color1, Color color2) {
        return new ColorFunc() {
            @Override
            Color apply(double value) {
                return color1.lerpTo(color2, value);
            }
        };
    }

    public static ColorFunc blend(List<Color> colors) {
        int count = colors.size();
        switch(count) {
            case 0:
                return blend(Colors.BLACK, Colors.BLACK);
            case 1:
                return blend(colors.get(0), colors.get(0));
            case 2:
                return blend(colors.get(0), colors.get(1));
            default:
                return new ColorFunc() {
                    @Override
                    Color apply(double value) {
                        if (value >= 1) {
                            return colors.get(count - 1);
                        } else if (value <= 0) {
                            return colors.get(0);
                        } else {
                            int segments = count - 1;
                            double s = value * segments;
                            int segment = (int) s;
                            double segmentFrac = modulo(s, 1);
                            Color c1 = colors.get(segment);
                            Color c2 = colors.get(segment + 1);
                            return c1.lerpTo(c2, segmentFrac);
                        }
                    }
                };
        }
    }

    abstract Color apply(double value);
}
