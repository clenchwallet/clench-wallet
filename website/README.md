# Clench.net

Static source for [clench.net](https://clench.net/). The production site has no
JavaScript, remote fonts, analytics, or hot-linked assets.

Run from this directory:

```sh
python3 -m http.server 8765
```

Then open <http://127.0.0.1:8765/>.

The app screenshots were captured from the official F-Droid v0.3.21 APK on the local Pixel 7 / Android 16 emulator. They contain no wallet secret, seed phrase, address, or transaction data.

The screenshots remain illustrative and are not evidence that their source APK
is the current release. The homepage's v0.3.28 signed-release links and version
copy must be deployed only after that GitHub release is public; F-Droid may
continue to display an older version while its independent build completes.

Deploy the contents of this directory to the web root. Preserve
`.well-known/autoconfig/mail/config-v1.1.xml`; it is the public mail-client
configuration for `clench.net`, not an obsolete website page.
