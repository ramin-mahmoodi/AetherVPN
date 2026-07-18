# AetherVPN for Android

AetherVPN is a modern and lightweight Android client for the Aether VPN protocol. It uses Android's native `VpnService` to capture network traffic and route it through the Aether SOCKS5 proxy, providing a seamless and secure internet experience.

## Features

*   **Aether Protocol:** Powered by the fast and secure Aether core.
*   **System-wide VPN:** Tunnels all device traffic without needing per-app configuration.
*   **Modern UI:** A clean, vibrant, and user-friendly interface.
*   **Multi-Architecture Support:** Built-in binaries for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
*   **Automated Builds:** CI/CD configured with GitHub Actions to automatically generate APKs on every push.

## Installation

You can download the latest pre-built APKs for your device architecture from the [Actions](../../actions) tab or the Releases page. 

## Build Instructions

To build the application locally:
1. Clone this repository.
2. Open the project in Android Studio or run Gradle from the command line:
   ```bash
   ./gradlew assembleDebug
   ```

## Acknowledgements & References

This project is built upon the incredible work of the following open-source projects. A huge thanks to their creators and contributors:

*   **[Aether Core](https://github.com/CluvexStudio/Aether):** The main VPN engine and protocol implementation.
*   **[Aether-GUI](https://github.com/MatinSenPai/Aether-GUI):** Inspirations and integrations based on the Aether GUI client.
*   **[tun2socks](https://github.com/xjasonlyu/tun2socks):** Used for intercepting network traffic and forwarding it to the Aether SOCKS proxy.

## License

This project is open-source. Please see the [LICENSE](LICENSE) file for more details.
