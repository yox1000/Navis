# Location Plugin

Provides high-accuracy GPS location updates using FusedLocationProvider.

## Permissions Required
- ACCESS_FINE_LOCATION
- ACCESS_COARSE_LOCATION  
- FOREGROUND_SERVICE
- POST_NOTIFICATIONS (Android 13+)

## Methods
- `start()`: Begin location updates every 2 seconds
- `stop()`: Stop location updates and foreground service

## Events
- `location`: Emitted every 2 seconds with location data

## Event Payload
```json
{
  "lat": number,
  "lon": number, 
  "accuracy_m": number,
  "bearing_deg": number,
  "ts": number
}
```

## Usage
```javascript
import { Capacitor } from '@capacitor/core';

const LocationPlugin = Capacitor.Plugins.LocationPlugin;

// Listen for location events
Capacitor.addListener('location', (data) => {
  console.log('Location:', data);
});

// Start tracking
await LocationPlugin.start();

// Stop tracking  
await LocationPlugin.stop();
```