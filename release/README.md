# In-app updates

The app reads `update.json` from the assets of the latest published GitHub release. It only downloads an APK after the user taps the update item in Settings.

To publish an update:

1. Set `upstreamReleaseNumber` to the upstream release number and increment `forkReleaseNumber` for each fork release based on it (reset it to `1` for a new upstream version). Independently increase `versionReleaseNumber` so the resulting Android version code is greater than the previous APK's code. Build the ARM64 release APK, signed with the same key as the installed app. For example, the first fork release based on upstream `26.09.4` is `26.09.4.1`.
2. Rename the APK to `infinitecable2-matrix.apk` without adding it to Git.
3. Copy the `update.json` template into a local release asset directory. Set its `versionCode` to the exact version code embedded in that APK and set `versionName` to the displayed version.
4. Calculate the APK's SHA-256 checksum and replace the `sha256` value.
5. Commit the source changes; keep the completed release manifest with the local APK asset.
6. Create a non-draft, non-prerelease GitHub release, explicitly mark it as the latest release, and attach both `infinitecable2-matrix.apk` and `update.json` as release assets.

The release tag should follow the app version, for example `v26.09.4.1`. The upstream `tools/release` scripts are deliberately disabled for this fork because they only understand three-part version names. The updater reads the manifest from:

`https://github.com/infiniteCable2/element-x-android/releases/latest/download/update.json`

The updater accepts only HTTPS, requires the APK to live beside the manifest, verifies the SHA-256 checksum, and checks the package name, version code, and signing certificate before opening Android's installer. Until the manifest's version code is greater than the installed app's code, no update is offered.

Keep the tracked `release/update.json` template unpublished. Version 26.09.2 still reads that legacy branch URL, but cannot download GitHub release assets from it. The complete manifest belongs only in the GitHub release assets.

`ELEMENT_X_UPDATE_MANIFEST_URL` or the ignored `updateManifestUrl` entry in `local.properties` can point development builds at a different manifest.
