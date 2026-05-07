// Ported from com.sparrowwallet:toucan 0.9.0 (Apache-2.0).
// Modified for Clench Wallet: package renamed for Android integration.

package net.clench.wallet.ui.lifehash.impl;

import net.clench.wallet.ui.lifehash.LifeHashVersion;

public enum Pattern {
    SNOWFLAKE, // Mirror around central axes.
    PINWHEEL, // Rotate around center.
    FIDUCIAL; // Identity.

    public static Pattern selectPattern(BitEnumerator entropy, LifeHashVersion version) {
        if (version == LifeHashVersion.FIDUCIAL || version == LifeHashVersion.GRAYSCALE_FIDUCIAL) {
            return FIDUCIAL;
        } else {
            if (entropy.next()) {
                return SNOWFLAKE;
            } else {
                return PINWHEEL;
            }
        }
    }
}
