# Clench.net

Static source for [clench.net](https://clench.net/). The production site has no
JavaScript, remote fonts, analytics, or hot-linked assets. A small standard-library
Python builder resolves shared templates before deployment, so browsers receive
complete static HTML without client-side includes.

## Build and preview

Run from this directory:

```sh
python3 build.py
python3 build.py --check
python3 -m unittest discover -s tests -v
python3 -m http.server --directory public 8765
```

Then open <http://127.0.0.1:8765/>. Generated files in `public/` are committed so
the exact deployable bytes remain reviewable. CI runs `build.py --check` and fails
if those files do not match their templates and configuration.

## Shared layout

- `site.json` contains site-wide values, release metadata, and the page manifest.
- `src/layout.html` owns the document structure and shared metadata.
- `src/partials/header.html` and `src/partials/footer.html` are the only copies of
  the global header and footer.
- `src/pages/` contains page-specific main content.
- `public/` is the generated deployment tree.

To add a page such as Terms of Service, add one page fragment under `src/pages/`
and one page record in `site.json`, then rebuild. The sitemap, shared layout, CSS
cache key, header, and footer are generated automatically.

## Release and F-Droid copy

The signed GitHub release version and tag come from the single release record in
`site.json`. Update that record only after the corresponding GitHub release is
public.

Do not hard-code a numeric F-Droid version. F-Droid publishes on its own cadence,
so its official package page is the source of truth for the version currently
available there. The website should keep its F-Droid link and versionless cadence
wording even when F-Droid temporarily trails the signed GitHub release.

The app screenshots were captured from the official F-Droid v0.3.21 APK on the
local Pixel 7 / Android 16 emulator. They contain no wallet secret, seed phrase,
address, or transaction data. They are illustrative and are not evidence that
their source APK is the current release.

## Deploy

Deploy only the contents of `public/` to the web root. The deployment must retain
`public/.well-known/autoconfig/mail/config-v1.1.xml`; it is the public mail-client
configuration for `clench.net`, not an obsolete website page. Do not deploy
`site.json`, templates, tests, the builder, or this README.
