# Pep Scout Android Wrapper

Native Android app that hosts the Navis web application and provides sensor/CV plugins.

## Prerequisites

### Software Requirements
- Android Studio Arctic Fox or newer
- Android SDK 24+ (minimum), 33+ (target)
- Java 11 or newer
- Node.js 16+ (for Capacitor CLI)

### Hardware Requirements  
- Android device with USB debugging enabled
- Camera and GPS capabilities
- Minimum 4GB RAM for CV processing

## Setup Instructions

### 1. Android Studio Setup
1. Install Android Studio from https://developer.android.com/studio
2. Open Android Studio and install required SDK components:
   - Android SDK Platform 33
   - Android SDK Build-Tools 33.0.0
   - Android SDK Platform-Tools
   - Android Emulator (optional)

### 2. Enable USB Debugging
1. On Android device: Settings → About Phone → tap Build Number 7 times
2. Go to Settings → Developer Options → Enable USB Debugging
3. Connect device to computer via USB
4. Allow USB debugging when prompted on device

### 3. Project Configuration
1. Open this project (`mobile/android`) in Android Studio
2. Wait for Gradle sync to complete
3. Create `local.properties` file in project root:
   ```
   sdk.dir=/path/to/Android/Sdk
   ```
4. Replace `<LAN_IP>` in `capacitor.config.json` with your development machine's IP address

## Building and Running

### Development Build
1. Start the frontend dev server:
   ```bash
   cd frontend && npm run dev
   ```
   Note the IP address (e.g., http://192.168.1.100:5173)

2. Update `capacitor.config.json` with your LAN IP:
   ```json
   "server": {
     "url": "http://192.168.1.100:5173",
     "cleartext": true
   }
   ```

3. In Android Studio:
   - Connect Android device via USB
   - Click Run button or press Shift+F10
   - Select your device from the list

### Production Build
1. Build frontend:
   ```bash
   cd frontend && npm run build
   ```

2. Remove server config from `capacitor.config.json`:
   ```json
   // Remove or comment out the server section
   ```

3. Sync Capacitor:
   ```bash
   npx cap sync android
   ```

4. Build signed APK in Android Studio

## Plugin Status

### ✅ Location Plugin
- **Status**: Implemented
- **Features**: GPS tracking with 2-second intervals  
- **Permissions**: Fine Location, Foreground Service

### ✅ Heading Plugin  
- **Status**: Implemented
- **Features**: Compass heading updates every 500ms
- **Permissions**: None (built-in sensors)

### ✅ QR Plugin
- **Status**: Implemented  
- **Features**: QR code scanning with CameraX + ML Kit
- **Permissions**: Camera

### ✅ CV Plugin
- **Status**: Implemented (model required)
- **Features**: Object detection for hazard warnings
- **Permissions**: Camera
- **Note**: Requires TensorFlow Lite model file (see CV Plugin README)

## Required Permissions
Add these to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />
```

## Testing Each Plugin

### Location Plugin Test
1. Open app, navigate to plugin test page
2. Tap "Start Location"
3. Check LogCat for location events every 2 seconds
4. Verify events appear in WebView console

### Heading Plugin Test  
1. Tap "Start Heading"
2. Rotate device, observe heading changes in LogCat
3. Values should be 0-360 degrees

### QR Plugin Test
1. Print QR codes from `frontend/public/qr/`
2. Tap "Scan QR"
3. Point camera at QR code
4. Verify correct payload returned (LIB:ENTR, LIB:ELEV, LIB:DEST)

### CV Plugin Test
1. Download TensorFlow Lite model to `app/src/main/assets/models/detect.tflite`
2. Tap "Start CV"  
3. Place chair or person in front of camera
4. Verify hazard event generated within 1-2 seconds
5. Check WebView for warning display

## Troubleshooting

### Common Issues

**Gradle Sync Failed**
- Check `local.properties` has correct SDK path
- Ensure Android SDK 33 is installed

**App Won't Connect to Dev Server**  
- Verify both devices on same WiFi network
- Check firewall settings on development machine
- Ensure frontend dev server is accessible (try browser on phone)

**Plugin Events Not Received**
- Check LogCat for plugin errors
- Verify permissions granted in device settings
- Test plugins one at a time

**CV Plugin Crashes**
- Ensure model file exists in correct path
- Check model file size and format
- Monitor memory usage during inference

### LogCat Commands
```bash
# Filter for plugin logs
adb logcat | grep "CVPlugin\|LocationPlugin\|HeadingPlugin\|QrPlugin"

# Clear logs
adb logcat -c
```

## File Structure
```
mobile/android/
├── app/
│   ├── src/main/assets/models/     # TensorFlow Lite models
│   ├── src/main/java/              # Plugin implementations
│   └── build.gradle                # App dependencies
├── plugins/                        # Plugin source code
│   ├── location/                   # GPS tracking
│   ├── heading/                    # Compass sensor  
│   ├── qr/                        # QR scanner
│   └── cv/                        # Computer vision
└── build.gradle                    # Project configuration
```