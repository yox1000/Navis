# QR Plugin

Provides QR code scanning using CameraX and ML Kit Barcode detection.

## Permissions Required
- CAMERA

## Methods
- `scan()`: Opens camera scanner and returns QR payload when detected

## Events
- `qrPayload`: Event emitted with QR code data (via method resolution)

## Return Payload
```json
{
  "payload": "string" // The decoded QR code text (e.g., "LIB:ENTR")
}
```

## Usage
```javascript
import { Capacitor } from '@capacitor/core';

const QrPlugin = Capacitor.Plugins.QrPlugin;

try {
  const result = await QrPlugin.scan();
  console.log('QR Code:', result.payload);
  // result.payload might be "LIB:ENTR", "LIB:ELEV", "LIB:DEST"
} catch (error) {
  console.error('QR scan failed:', error);
}
```

## Implementation Details
- Uses CameraX for camera preview
- ML Kit Barcode API for QR detection
- Automatically closes when QR code is found
- Supports QR_CODE format only for performance
- Single-shot scanning (returns first valid QR found)

## Dependencies Required in build.gradle
```gradle
implementation 'androidx.camera:camera-core:1.3.0'
implementation 'androidx.camera:camera-camera2:1.3.0'
implementation 'androidx.camera:camera-lifecycle:1.3.0'
implementation 'androidx.camera:camera-view:1.3.0'
implementation 'com.google.mlkit:barcode-scanning:17.2.0'
```