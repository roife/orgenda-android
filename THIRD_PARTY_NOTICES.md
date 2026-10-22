# Third-party sources and notices

## Org grammar and scanner

Copied from the actual iOS working tree at `/Users/roifewu/.config/orgenda/Packages/OrgTreeSitter/`, based on commit `ee36dde2dcad95f930c088910f02d5718a9d2770` plus the uncommitted changes captured in `docs/ios-working-tree.patch`. The file-level SHA-256 baseline is `docs/ios-baseline.json`; grammar-specific hashes are `docs/native-sources.json`.

`vendor/tree-sitter-org/` contains grammar.js, generated grammar.json/parser.c, scanner.c, its public header and parser support headers. The grammar is original to Orgenda, MIT, copyright 2026 Orgenda contributors; see its retained LICENSE. Tree-sitter support headers retain their own MIT license in `tree_sitter/LICENSE`. No parser regeneration or formatting was performed during this port.

## Tree-sitter runtime

`vendor/tree-sitter/lib/` and LICENSE are copied from the official [tree-sitter/tree-sitter v0.26.3](https://github.com/tree-sitter/tree-sitter/tree/v0.26.3) release, MIT, copyright Max Brunsfeld. Runtime and grammar both use ABI 15. The Android-specific JNI adapter is in `native/orgenda.c`.

## Native math and fonts

[RaTeX v0.1.14](https://github.com/erweixin/RaTeX/tree/v0.1.14) is resolved as `io.github.erweixin:ratex-android:0.1.14`. Its Android AAR provides native rendering and precompiled `libratex_ffi.so`.

RaTeX is MIT, copyright erweixin. Bundled KaTeX fonts use SIL Open Font License 1.1, separately from the renderer. Full upstream licenses and font notices are included in `app/src/main/assets/licenses/`, so they are also present in the APK. The Android app does not modify the fonts.

## Other Android dependencies

Kotlin, kotlinx.coroutines, kotlinx.serialization, AndroidX (Compose, Material3, Activity, Lifecycle, AppCompat, Room, WorkManager, DocumentFile), OkHttp/Okio and Coil are declared with fixed versions/BOM in the Gradle files. These libraries use Apache License 2.0; upstream copyright notices remain with their artifacts. The full Apache license is included in APK assets. JUnit, Robolectric and MockWebServer are test-only dependencies and are not included as application functionality.

This notice does not assign a new license to the application as a whole. The original iOS source and its tests are referenced as compatibility evidence rather than relicensed.

## Parity update baseline and native code highlighting

The 0.1.1 audit is pinned separately to iOS commit `9b70a360ed38bac6eb36819aedd891be5126fab7` plus `docs/parity/current-ios-working-tree.patch`. `docs/parity/ios-file-inventory.json` records all 223 source files and their Android counterparts. Vendored grammar/scanner/generated parser and support headers still match this baseline byte for byte. The original 0.1.0 dirty-worktree snapshot remains intact.

The configuration-conversion prompt and behavioral ports derive from the original Orgenda sources; their provenance is recorded in the file comparison rather than assigned a different application license.

[Prism4j 2.0.0](https://github.com/noties/Prism4j/tree/v2.0.0) (Apache 2.0) supplies JVM tokenization and bundled grammars derived from [Prism](https://github.com/PrismJS/prism/blob/v1.17.1/LICENSE) (MIT, copyright 2012 Lea Verou). Full license texts are shipped in `app/src/main/assets/licenses/Prism4j-Apache-2.0.txt` and `Prism-MIT.txt`. Kotlin/Java code uses native TextView spans; no JavaScript executes in previews.

## Native TIFF previews

TIFF previews use unmodified [libtiff 4.7.1](https://download.osgeo.org/libtiff/tiff-4.7.1.tar.gz) and [libjpeg-turbo 3.2.0](https://github.com/libjpeg-turbo/libjpeg-turbo/releases/tag/3.2.0), built from source with the project's NDK for both packaged ABIs. Download URLs and archive SHA-256 values are pinned in `vendor/image-codecs.json`; original sources and licenses are in `vendor/libtiff` and `vendor/libjpeg-turbo`. License texts are also included in APK assets. This software is based in part on the work of the Independent JPEG Group. Android system zlib provides Deflate support. No legacy third-party TIFF binary is packaged.
