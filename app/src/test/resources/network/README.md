# Public loopback TLS fixture

`public-localhost-fixture.p12` is newly generated test-only RSA material, not an application,
release, user, or server identity. The deliberately public password is `public-test-fixture`.
The certificate covers DNS `localhost` only so tests also reject a `127.0.0.1` URL.
Only JVM unit tests load it; this directory is not packaged in the application APK.
