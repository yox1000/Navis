# CV Plugin

On-device computer vision for hazard detection using TensorFlow Lite.

## Permissions Required
- CAMERA

## Methods
- `start()`: Begin CV analysis on camera feed
- `stop()`: Stop CV analysis

## Events
- `hazard`: Emitted when objects are detected in central region

## Event Payload
```json
{
  "id": "hazard_1731100000",
  "ts": 1731100000,
  "where": {
    "type": "indoor"
  },
  "kind": "obstacle|moving_object|stair|curb|door",
  "label": "person|chair|bike|unknown", 
  "severity": "info|warn|danger",
  "ttl_s": 3
}
```

## Usage
```javascript
import { Capacitor } from '@capacitor/core';

const CVPlugin = Capacitor.Plugins.CVPlugin;

// Listen for hazard events
Capacitor.addListener('hazard', (data) => {
  console.log('Hazard detected:', data.label, data.severity);
  
  // Show warning and vibrate
  showHazardWarning(data);
  navigator.vibrate(200);
});

// Start CV detection
await CVPlugin.start();

// Stop CV detection
await CVPlugin.stop();
```

## Implementation Details
- Uses TensorFlow Lite for on-device inference
- Targets 15-25 FPS on mid-range phones
- Center-crop filtering: only detects objects in center 60% of frame
- Minimum box area threshold to avoid distant objects
- Severity based on object size (height ratio)
- 500ms analysis interval to balance performance and battery

## Model Requirements
Place the following files in `/android/app/src/main/assets/models/`:
- `detect.tflite` - Quantized object detection model (< 10MB)
- `labels.txt` - COCO class labels (provided)

## Recommended Models
- EfficientDet-Lite0: https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1
- SSD MobileNet v2: https://tfhub.dev/tensorflow/lite-model/ssd_mobilenet_v2/1/metadata/2

## Dependencies Required in build.gradle
```gradle
implementation 'org.tensorflow:tensorflow-lite:2.14.0'
implementation 'org.tensorflow:tensorflow-lite-support:0.4.4'
implementation 'org.tensorflow:tensorflow-lite-metadata:0.4.4'
implementation 'androidx.camera:camera-core:1.3.0'
implementation 'androidx.camera:camera-camera2:1.3.0'
implementation 'androidx.camera:camera-lifecycle:1.3.0'
```

## Performance Notes
- Model size vs accuracy tradeoff
- CPU inference only (no GPU required)
- Optimized for common indoor/outdoor objects
- Battery usage is moderate when enabled
- Recommend user control via "Safety Camera" toggle