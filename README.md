# Bluetooth File Share

Android Kotlin app for direct file transfer between paired Android devices over Bluetooth Classic RFCOMM.

## Use
1. Install the APK on both phones.
2. Pair the phones in Android Bluetooth settings.
3. On the receiving phone tap **Receive files**.
4. On the sending phone tap **Select file to send**, choose a file, then tap the receiver.
5. Received files are stored in **Downloads/BluetoothFileShare**.

The receiver should be waiting before the sender connects.

## Build
GitHub Actions builds a debug APK and uploads it as an artifact.
