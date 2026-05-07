// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

import net.clench.wallet.ui.lifehash.LifeHashVersion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static net.clench.wallet.ui.lifehash.impl.Utils.lerp;
import static net.clench.wallet.ui.lifehash.impl.Utils.modulo;

public class Gradients {
    static ColorFunc grayscale = ColorFunc.blend(Colors.BLACK, Colors.WHITE);

    static ColorFunc selectGrayscale(BitEnumerator entropy) {
        return entropy.next() ? grayscale : ColorFunc.reverse(grayscale);
    }

    static ColorFunc makeHue = new ColorFunc() {
            @Override
            Color apply(double value) {
                return new HSBColor(value).color();
            }
        };

    static ColorFunc spectrum = ColorFunc.blend(Arrays.asList(
            Color.fromUint8Values(0, 168, 222),
            Color.fromUint8Values(51, 51, 145),
            Color.fromUint8Values(233, 19, 136),
            Color.fromUint8Values(235, 45, 46),
            Color.fromUint8Values(253, 233, 43),
            Color.fromUint8Values(0, 158, 84),
            Color.fromUint8Values(0, 168, 222)
    ));

    static ColorFunc spectrumCmykSafe = ColorFunc.blend(Arrays.asList(
            Color.fromUint8Values(0, 168, 222),
            Color.fromUint8Values(41, 60, 130),
            Color.fromUint8Values(210, 59, 130),
            Color.fromUint8Values(217, 63, 53),
            Color.fromUint8Values(244, 228, 81),
            Color.fromUint8Values(0, 158, 84),
            Color.fromUint8Values(0, 168, 222)
    ));

    static Color adjustForLuminance(Color color, Color contrastColor) {
        double lum = color.luminance();
        double contrastLum = contrastColor.luminance();
        double threshold = 0.6;
        double offset = Math.abs(lum - contrastLum);
        if (offset > threshold) {
            return color;
        }
        double boost = 0.7;
        double t = lerp(0, threshold, boost, 0, offset);
        if (contrastLum > lum) {
            // darken this color
            return color.darken(t).burn(t * 0.6);
        } else {
            // lighten this color
            return color.lighten(t).burn(t * 0.6);
        }
    }

    static ColorFunc monochromatic(BitEnumerator entropy, ColorFunc hueGenerator) {
        double hue = entropy.nextFrac();
        boolean isTint = entropy.next();
        boolean isReversed = entropy.next();
        double keyAdvance = entropy.nextFrac() * 0.3 + 0.05;
        double neutralAdvance = entropy.nextFrac() * 0.3 + 0.05;

        Color keyColor = hueGenerator.apply(hue);

        double contrastBrightness;
        if (isTint) {
            contrastBrightness = 1;
            keyColor = keyColor.darken(0.5);
        } else {
            contrastBrightness = 0;
        }
        Color neutralColor = grayscale.apply(contrastBrightness);

        Color keyColor2 = keyColor.lerpTo(neutralColor, keyAdvance);
        Color neutralColor2 = neutralColor.lerpTo(keyColor, neutralAdvance);

        ColorFunc gradient = ColorFunc.blend(keyColor2, neutralColor2);
        return isReversed ? ColorFunc.reverse(gradient) : gradient;
    }

    static ColorFunc monochromaticFiducial(BitEnumerator entropy) {
        double hue = entropy.nextFrac();
        boolean isReversed = entropy.next();
        boolean isTint = entropy.next();

        Color contrastColor = isTint ? Colors.WHITE : Colors.BLACK;
        Color keyColor = adjustForLuminance(spectrumCmykSafe.apply(hue), contrastColor);

        ColorFunc gradient = ColorFunc.blend(Arrays.asList(keyColor, contrastColor, keyColor));
        return isReversed ? ColorFunc.reverse(gradient) : gradient;
    }

    static ColorFunc complementary(BitEnumerator entropy, ColorFunc hueGenerator) {
        double spectrum1 = entropy.nextFrac();
        double spectrum2 = modulo(spectrum1 + 0.5, 1);
        double lighterAdvance = entropy.nextFrac() * 0.3;
        double darkerAdvance = entropy.nextFrac() * 0.3;
        boolean isReversed = entropy.next();

        Color color1 = hueGenerator.apply(spectrum1);
        Color color2 = hueGenerator.apply(spectrum2);

        double luma1 = color1.luminance();
        double luma2 = color2.luminance();

        Color darkerColor;
        Color lighterColor;
        if (luma1 > luma2) {
            darkerColor = color2;
            lighterColor = color1;
        } else {
            darkerColor = color1;
            lighterColor = color2;
        }

        Color adjustedLighterColor = lighterColor.lighten(lighterAdvance);
        Color adjustedDarkerColor = darkerColor.darken(darkerAdvance);

        ColorFunc gradient = ColorFunc.blend(adjustedDarkerColor, adjustedLighterColor);
        return isReversed ? ColorFunc.reverse(gradient) : gradient;
    }

    static ColorFunc complementaryFiducial(BitEnumerator entropy) {
        double spectrum1 = entropy.nextFrac();
        double spectrum2 = modulo((spectrum1 + 0.5), 1);
        boolean is_tint = entropy.next();
        boolean is_reversed = entropy.next();
        boolean neutral_color_bias = entropy.next();

        Color neutral_color = is_tint ? Colors.WHITE : Colors.BLACK;
        Color color1 = spectrumCmykSafe.apply(spectrum1);
        Color color2 = spectrumCmykSafe.apply(spectrum2);

        Color biased_neutral_color = neutral_color.lerpTo(neutral_color_bias ? color1 : color2, 0.2).burn(0.1);
        ColorFunc gradient = ColorFunc.blend(Arrays.asList(
                adjustForLuminance(color1, biased_neutral_color),
                biased_neutral_color,
                adjustForLuminance(color2, biased_neutral_color)
        ));
        return is_reversed ? ColorFunc.reverse(gradient) : gradient;
    }

    static ColorFunc triadic(BitEnumerator entropy, ColorFunc hueGenerator) {
        double spectrum1 = entropy.nextFrac();
        double spectrum2 = modulo(spectrum1 + 1.0 / 3, 1);
        double spectrum3 = modulo((spectrum1 + 2.0 / 3), 1);
        double lighter_advance = entropy.nextFrac() * 0.3;
        double darker_advance = entropy.nextFrac() * 0.3;
        boolean is_reversed = entropy.next();

        Color color1 = hueGenerator.apply(spectrum1);
        Color color2 = hueGenerator.apply(spectrum2);
        Color color3 = hueGenerator.apply(spectrum3);
        List<Color> colors = new ArrayList<>();
        colors.add(color1);
        colors.add(color2);
        colors.add(color3);
        colors.sort(Comparator.comparingDouble(Color::luminance));

        Color darker_color = colors.get(0);
        Color middle_color = colors.get(1);
        Color lighter_color = colors.get(2);

        Color adjusted_lighter_color = lighter_color.lighten(lighter_advance);
        Color adjusted_darker_color = darker_color.darken(darker_advance);

        ColorFunc gradient = ColorFunc.blend(Arrays.asList(adjusted_lighter_color, middle_color, adjusted_darker_color));
        return is_reversed ? ColorFunc.reverse(gradient) : gradient;
    }

    static ColorFunc triadicFiducial(BitEnumerator entropy) {
        double spectrum1 = entropy.nextFrac();
        double spectrum2 = (spectrum1 + 1.0 / 3) % 1;
        double spectrum3 = (spectrum1 + 2.0 / 3) % 1;
        boolean isTint = entropy.next();
        int neutralInsertIndex = entropy.nextUint8() % 2 + 1;
        boolean isReversed = entropy.next();

        Color neutralColor = isTint ? Colors.WHITE : Colors.BLACK;

        List<Color> colors = new ArrayList<>(Arrays.asList(spectrumCmykSafe.apply(spectrum1), spectrumCmykSafe.apply(spectrum2), spectrumCmykSafe.apply(spectrum3)));
        switch(neutralInsertIndex) {
            case 1 -> {
                colors.set(0, adjustForLuminance(colors.get(0), neutralColor));
                colors.set(1, adjustForLuminance(colors.get(1), neutralColor));
                colors.set(2, adjustForLuminance(colors.get(2), colors.get(1)));
            }
            case 2 -> {
                colors.set(1, adjustForLuminance(colors.get(1), neutralColor));
                colors.set(2, adjustForLuminance(colors.get(2), neutralColor));
                colors.set(0, adjustForLuminance(colors.get(0), colors.get(1)));
            }
            default -> throw new IllegalArgumentException("Internal error.");
        }

        colors.add(neutralInsertIndex, neutralColor);

        ColorFunc gradient = ColorFunc.blend(colors);
        return isReversed ? ColorFunc.reverse(gradient) : gradient;
    }

    static ColorFunc analogous(BitEnumerator entropy, ColorFunc hueGenerator) {
        double spectrum1 = entropy.nextFrac();
        double spectrum2 = modulo(spectrum1 + 1.0 / 12, 1);
        double spectrum3 = modulo(spectrum1 + 2.0 / 12, 1);
        double spectrum4 = modulo(spectrum1 + 3.0 / 12, 1);
        double advance = entropy.nextFrac() * 0.5 + 0.2;
        boolean isReversed = entropy.next();

        Color color1 = hueGenerator.apply(spectrum1);
        Color color2 = hueGenerator.apply(spectrum2);
        Color color3 = hueGenerator.apply(spectrum3);
        Color color4 = hueGenerator.apply(spectrum4);

        Color darkestColor;
        Color darkColor;
        Color lightColor;
        Color lightestColor;

        if (color1.luminance() < color4.luminance()) {
            darkestColor = color1;
            darkColor = color2;
            lightColor = color3;
            lightestColor = color4;
        } else {
            darkestColor = color4;
            darkColor = color3;
            lightColor = color2;
            lightestColor = color1;
        }

        Color adjustedDarkestColor = darkestColor.darken(advance);
        Color adjustedDarkColor = darkColor.darken(advance / 2);
        Color adjustedLightColor = lightColor.lighten(advance / 2);
        Color adjustedLightestColor = lightestColor.lighten(advance);

        ColorFunc gradient = ColorFunc.blend(Arrays.asList(adjustedDarkestColor, adjustedDarkColor, adjustedLightColor, adjustedLightestColor));
        return isReversed ? ColorFunc.reverse(gradient) : gradient;
    }

    static ColorFunc analogousFiducial(BitEnumerator entropy) {
        double spectrum1 = entropy.nextFrac();
        double spectrum2 = modulo(spectrum1 + 1.0 / 10, 1);
        double spectrum3 = modulo(spectrum1 + 2.0 / 10, 1);
        boolean isTint = entropy.next();
        int neutralInsertIndex = entropy.nextUint8() % 2 + 1;
        boolean isReversed = entropy.next();

        Color neutralColor = isTint ? Colors.WHITE : Colors.BLACK;

        List<Color> colors = new ArrayList<>(Arrays.asList(spectrumCmykSafe.apply(spectrum1), spectrumCmykSafe.apply(spectrum2), spectrumCmykSafe.apply(spectrum3)));
        switch(neutralInsertIndex) {
            case 1 -> {
                colors.set(0, adjustForLuminance(colors.get(0), neutralColor));
                colors.set(1, adjustForLuminance(colors.get(1), neutralColor));
                colors.set(2, adjustForLuminance(colors.get(2), colors.get(1)));
            }
            case 2 -> {
                colors.set(1, adjustForLuminance(colors.get(1), neutralColor));
                colors.set(2, adjustForLuminance(colors.get(2), neutralColor));
                colors.set(0, adjustForLuminance(colors.get(0), colors.get(1)));
            }
            default -> throw new IllegalStateException("Internal error");
        }
        colors.add(neutralInsertIndex, neutralColor);

        ColorFunc gradient = ColorFunc.blend(colors);
        return isReversed ? ColorFunc.reverse(gradient) : gradient;
    }

    public static ColorFunc selectGradient(BitEnumerator entropy, LifeHashVersion version) {
        if(version == LifeHashVersion.GRAYSCALE_FIDUCIAL) {
            return selectGrayscale(entropy);
        }

        int value = entropy.nextUint2();

        return switch(value) {
            case 0 -> switch(version) {
                case VERSION1 -> monochromatic(entropy, makeHue);
                case VERSION2, DETAILED -> monochromatic(entropy, spectrumCmykSafe);
                case FIDUCIAL -> monochromaticFiducial(entropy);
                case GRAYSCALE_FIDUCIAL -> grayscale;
            };
            case 1 -> switch(version) {
                case VERSION1 -> complementary(entropy, spectrum);
                case VERSION2, DETAILED -> complementary(entropy, spectrumCmykSafe);
                case FIDUCIAL -> complementaryFiducial(entropy);
                case GRAYSCALE_FIDUCIAL -> grayscale;
            };
            case 2 -> switch(version) {
                case VERSION1 -> triadic(entropy, spectrum);
                case VERSION2, DETAILED -> triadic(entropy, spectrumCmykSafe);
                case FIDUCIAL -> triadicFiducial(entropy);
                case GRAYSCALE_FIDUCIAL -> grayscale;
            };
            case 3 -> switch(version) {
                case VERSION1 -> analogous(entropy, spectrum);
                case VERSION2, DETAILED -> analogous(entropy, spectrumCmykSafe);
                case FIDUCIAL -> analogousFiducial(entropy);
                case GRAYSCALE_FIDUCIAL -> grayscale;
            };
            default -> grayscale;
        };
    }
}
