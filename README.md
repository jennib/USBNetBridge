# USB Net Bridge

USB Net Bridge is an Android application that bridges a connected USB serial device to your local network. It allows you to stream video, send and receive serial data, and interact with your USB device wirelessly from any computer on the same network.

## Features

- **Auto-connects** to the first available USB serial device on startup.
- **MJPEG Video Streaming**: Streams the device's camera feed over HTTP on port `8889`.
- **Web Interface**: Provides a secure web interface over HTTPS on port `8887` to interact with the serial device via WebSockets.
- **TCP Proxy**: Offers a raw TCP bridge to the serial device on port `9999`.
- **Configurable Serial Port**: Baud rate, data bits, stop bits, and parity can be adjusted in the app's settings.

## Getting Started

### Prerequisites

- An Android device with USB On-The-Go (OTG) support.
- A USB OTG adapter to connect USB-A devices to your Android phone/tablet.
- A USB serial device (e.g., Arduino, ESP32, FTDI adapter).

### Installation & Usage

1.  **Build and Install**: Build the project in Android Studio and install the APK on your Android device.

2.  **Connect Hardware**: Connect the USB serial device to your Android device using the OTG adapter.

3.  **Launch the App**: Open the USB Net Bridge app.

4.  **Grant Permissions**: The app will request permission to access the USB device and use the camera. Please grant these permissions for the app to function correctly.

5.  **Check Connection Status**: Once a connection is established, the app's main screen will display the IP address of your Android device and the status of the various servers.

    *Your app's main screen will look something like this:*

    `[Screenshot of the main screen with connection info]`

### Accessing the Services

-   **Video Stream**: Open a media player like VLC or a web browser and navigate to `http://<your_android_device_ip>:8889` to see the live MJPEG video stream.

-   **Web Interface (HTTPS/WebSocket)**: 
    1. Open a web browser and navigate to `https://<your_android_device_ip>:8887`.
    2. You will see a security warning because the app uses a self-signed SSL certificate. Proceed past the warning.
    3. The web page provides a terminal-like interface to send and receive data from your serial device over a secure WebSocket connection.

-   **TCP Proxy**: Use a TCP client like `netcat` or `telnet` to establish a raw, bidirectional connection to your serial device.
    ```sh
    netcat <your_android_device_ip> 9999
    ```

## Screenshots

*Add a screenshot of the main user interface.*

`[Screenshot placeholder 1: Main screen of the app]`

*Add a screenshot of the video stream working in a browser or VLC.*

`[Screenshot placeholder 2: Video stream in action]`

*Add a screenshot of the web interface.*

`[Screenshot placeholder 3: Web interface for serial communication]`
