# Vosk 16 KB Page-Size Fix (Option A)

Android 15+ requires native `.so` libraries to be compatible with 16 KB page size.
Prebuilt `com.alphacephei:vosk-android` may contain `libvosk.so` that is not 16 KB aligned on some ABIs.

## Why Pixel x86_64 emulator complains but some players do not

Upstream `vosk-api/android/lib/build-vosk.sh` (as of early 2026) applies 16 KB–style linker flags only for **`arm64-v8a`**. **`x86_64`**, **`x86`**, and **`armeabi-v7a`** use empty `PAGESIZE_LDFLAGS`, so **`lib/x86_64/libvosk.so`** often fails the system ELF check on Android 15+ x86_64 images.

**JNA:** use the **`@aar`** dependency (see `aday-android/build.gradle.kts`) and keep **`jna`** in `libs.versions.toml` aligned with upstream Vosk’s Android module (currently **5.18.1**). The plain JAR does not ship Android `libjnidispatch.so`; older AARs may still fail 16 KB checks until JNA ships updated binaries—bumping JNA is the first step.

## Current setup (vendored AAR in repo)

The project may vendor a copy of the official Maven AAR as:

- `aday-android/libs/vosk-android-16kb.aar`  
  (often mirrored from `com.alphacephei:vosk-android:0.3.75` on Maven Central)

`aday-android/build.gradle.kts` **prefers this file** when it exists; otherwise it uses the version from `gradle/libs.versions.toml`.

## Rebuild `libvosk.so` with 16 KB flags for **all** ABIs (including x86_64)

This cannot be done from Gradle alone; you must re-run the upstream Android native build with patched linker flags.

1. **Linux or WSL** (the upstream script is Bash and downloads toolchains). Install **Android NDK** (upstream’s `android/lib/build.gradle` pins an `ndkVersion`; match that or use the same major version).

2. Clone **vosk-api** (same tag as your Maven version, e.g. `master` / release matching `0.3.75`):

   ```bash
   git clone --depth 1 https://github.com/alphacep/vosk-api.git
   cd vosk-api
   git apply /path/to/ADayApp/scripts/vosk/0001-16kb-page-size-x86.patch
   ```

   The patch sets `PAGESIZE_LDFLAGS` for **`armeabi-v7a`**, **`x86_64`**, and **`x86`** to the same style as **`arm64-v8a`**:

   `-Wl,-z,common-page-size=4096 -Wl,-z,max-page-size=16384`

3. From `vosk-api/android/lib`, set **`ANDROID_NDK_HOME`** to your NDK root and run **`./build-vosk.sh`** (long build: OpenBLAS, Kaldi, Vosk). This fills `src/main/jniLibs/<abi>/libvosk.so`.

4. Build the **Android library AAR** with Gradle from `vosk-api/android` (see upstream `README.md`), or copy the generated **`jniLibs`** into a local AAR layout and replace your vendored **`vosk-android-16kb.aar`**.

5. Copy the resulting AAR to **`aday-android/libs/vosk-android-16kb.aar`** (or drop the file and rely on **`implementation(libs.vosk.android)`** from Maven once you publish or use a local Maven repo).

6. Rebuild the app and re-test on **Pixel API 35+ x86_64**.

## Verify packaged libs

From project root:

```powershell
tar -tf "aday-android/build/outputs/apk/debug/aday-android-debug.apk" | Select-String "lib/"
```

On Linux/macOS you can also inspect ELF program headers with **`llvm-readelf -l`** on `lib/x86_64/libvosk.so` and confirm LOAD alignment expectations for your target OS build.
