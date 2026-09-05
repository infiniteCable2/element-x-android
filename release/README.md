# In-app updates

The app reads `update.json` from this directory. It only downloads an APK after the user taps the update item in Settings.

To publish an update:

1. Build and sign the APK with the same signing key as the installed app.
2. Copy the APK to this directory as `infinitecable2-matrix.apk`.
3. Set `versionCode` in `update.json` to the exact version code embedded in that APK and set `versionName` to the displayed version.
4. Calculate the APK's SHA-256 checksum and replace the `sha256` value.
5. Commit the APK and manifest together.

The updater accepts only HTTPS, requires the APK to live beside this manifest, verifies the SHA-256 checksum, and checks the package name, version code, and signing certificate before opening Android's installer. Until the manifest's version code is greater than the installed app's code, no update is offered.

`ELEMENT_X_UPDATE_MANIFEST_URL` or the ignored `updateManifestUrl` entry in `local.properties` can point development builds at a different copy of the manifest.
