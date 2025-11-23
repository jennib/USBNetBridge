# USB Net Bridge

USB Net Bridge is an Android application that bridges a connected USB serial device to your local network. It allows you to stream video, send and receive serial data, and interact with your USB device wirelessly from any computer on the same network.

## Features

- **Auto-connects** to the first available USB serial device on startup.
- 
- **Web Interface**: Serves a webpage on port 8888 that includes video and audio, serial log, macros and the ability
  to send serial commands.
- **TCP Proxy**: Offers a raw TCP bridge to the serial device on port `8889`.
- **Configurable Serial Port**: Baud rate, data bits, stop bits, and parity can be adjusted in the app's settings.

## Getting Started

### Prerequisites

- An Android device with USB On-The-Go (OTG) support
- A USB adapter to let you connect your serial device, and maybe power, to the Android device.
- A USB serial device (e.g., Arduino, ESP32, FTDI adapter).

### Installation ###

1.  **Build and Install**: Build the project in Android Studio and install the APK on your Android device.
2.  Signed APK's in the release section of github.

*Add a screenshot of the video stream working in a browser or VLC.*

`[Screenshot placeholder 2: Video stream in action]`

*Add a screenshot of the web interface.*

`[Screenshot placeholder 3: Web interface for serial communication]`
