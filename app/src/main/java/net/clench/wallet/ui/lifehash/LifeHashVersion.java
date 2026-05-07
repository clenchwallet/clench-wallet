// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash;

public enum LifeHashVersion {
    VERSION1, // DEPRECATED. Uses HSB gamut. Not CMYK-friendly. Has some minor gradient bugs.
    VERSION2, // CMYK-friendly gamut. Recommended for most purposes.
    DETAILED, // Double resolution. CMYK-friendly gamut gamut.
    FIDUCIAL, // Optimized for generating machine-vision fiducials. High-contrast. CMYK-friendly gamut.
    GRAYSCALE_FIDUCIAL // Optimized for generating machine-vision fiducials. High-contrast.
}
