# Heading Plugin

Provides device heading/azimuth using rotation vector sensor.

## Permissions Required
None (uses built-in sensors)

## Methods
- `start()`: Begin heading updates every 500ms
- `stop()`: Stop heading updates

## Events  
- `heading`: Emitted every 500ms with azimuth data

## Event Payload
```json
{
  "azimuth": number, // 0-360 degrees, 0=North, 90=East, 180=South, 270=West
  "ts": number       // timestamp in milliseconds
}
```

## Usage
```javascript
import { Capacitor } from '@capacitor/core';

const HeadingPlugin = Capacitor.Plugins.HeadingPlugin;

// Listen for heading events
Capacitor.addListener('heading', (data) => {
  console.log('Heading:', data.azimuth + '°');
});

// Start tracking
await HeadingPlugin.start();

// Stop tracking
await HeadingPlugin.stop();
```

## Notes
- Uses TYPE_ROTATION_VECTOR sensor for best accuracy
- Falls back to deprecated TYPE_ORIENTATION if rotation vector unavailable
- Azimuth is normalized to 0-360 degrees range
- 500ms update interval to balance accuracy and battery