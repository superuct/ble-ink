# BLE Ink — E-Paper Display Controller

An Android app for controlling **E-Ink (EPD) displays** over **Bluetooth Low Energy (BLE)**. Designed for nRF5x-based EPD driver boards, supporting multi-color displays with dithering, text rendering, and high-speed image transfer.

## Features

- **BLE Connectivity** — Scan, connect, and manage devices with auto-detect of 4-character device IDs (e.g., `NRF_EPD_xxxx`)
- **Multi-Color Support** — Black/white, three-color (BWR), and four-color (BWRY) EPD panels
- **Image Transfer** — Select images from gallery; auto-rotate, scale, and center on the display canvas
- **Floyd-Steinberg Dithering** — High-quality dithering with configurable threshold, gamma, and legacy mode
- **Text Rendering** — Type text directly, choose font, size, bold/italic, with auto-wrapping and centering
- **Real-Time Preview** — See the dithered result before sending to the display
- **Optimized BLE Transfer** — MTU negotiation, 2M PHY, high-priority connection, and interleaved write pacing for maximum throughput
- **Transfer Progress** — Real-time progress bar with speed indicator

## Supported Displays

Configured for **3.98-inch four-color EPD** (768×552), driver `14`. Compatible with other nRF5x-driven EPD panels.

## Tech Stack

- **Language:** Kotlin
- **BLE:** Android BluetoothGATT with custom write pacing
- **Image Processing:** Custom Floyd-Steinberg dithering, 2bpp four-color encoding
- **UI:** Material Design, ViewBinding

## Building

```bash
./gradlew assembleDebug
```

APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

## License

MIT
