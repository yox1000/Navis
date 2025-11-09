# Wireless Debugging Checklist

1. Connect phone and dev machine to the same Wi-Fi network.
2. On the phone: Settings → Developer options → enable "Wireless debugging".
3. Tap "Wireless debugging" → "Pair device with pairing code".
4. In Android Studio: Device Manager → ⋮ menu → "Pair device using Wi-Fi".
5. Enter the IP address/port and pairing code from the phone.
6. After pairing, choose "Wireless debugging" entry → "Allow".
7. In Device Manager, select the paired device and click "Connect".
8. Once "Online", pick it in the run/debug dropdown and deploy with Shift+F10.
