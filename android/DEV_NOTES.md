# Android MapLibre Build Notes

- Maps are rendered with [OSMDroid](https://github.com/osmdroid/osmdroid) backed by MapTiler's high-detail Streets v2 raster tiles (`strings.xml:maptiler_key`). Swap the style URL or key there for different looks.
- Fused location (Google Play Services) still drives the position marker for accuracy; swap to Android `LocationManager` later if Play Services is unavailable.
- Dev speed tips:
  - Enable Gradle configuration cache and build cache inside `android/gradle.properties` (`org.gradle.configuration-cache=true`, `org.gradle.caching=true`).
  - Use Android Studio's "Apply Changes" button instead of full reinstalls for Compose tweaks.
  - Keep one physical device connected (USB or wireless) and avoid restarting the IDE between runs to let the Gradle daemon stay warm.
