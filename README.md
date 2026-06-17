NearMe

Talk to people around you. No internet needed.

NearMe is an Android app that lets you discover nearby people, send messages, share files, and create group conversations all without internet, servers, or accounts. Everything travels directly from your phone to theirs using Bluetooth and Wi-Fi, the way local communication should work.

Whether you're in a lecture hall with no signal, a remote area with no coverage, or a country where the internet has been shut down  NearMe keeps you connected to the people physically around you.

Why NearMe?

Most messaging apps assume you have internet. But what happens when you don't?

NearMe was built for exactly that scenario. It uses the radios already inside your phone Bluetooth and Wi-Fi  to create direct connections between devices. No router, no cell tower, no cloud server sits between you and the person you're talking to. Your messages never leave the room.

This makes NearMe useful in situations like campus environments where Wi-Fi is unreliable, remote outdoor areas with no cell coverage, disaster and emergency scenarios where infrastructure is down, and regions experiencing internet shutdowns or censorship.

What It Does

Discover people nearby.
Open the app and NearMe automatically finds other NearMe users around you. You'll see their name and how close they are. On supported devices, the extended discovery mode reaches further using Wi-Fi Aware.

Chat one-on-one.
Tap on someone to start a private conversation. Messages are delivered instantly with read receipts (✓ sent, ✓✓ delivered). The connection is encrypted by the underlying transport  no one between you can read your messages.

Create group conversations.
Name a group, pick members from nearby users, and start chatting. The group creator's device acts as a relay hub, forwarding messages and files between all members. New people can be added mid-conversation, and system messages keep everyone informed when someone joins or leaves.

Share files directly.
Send photos, videos, and documents to individuals or groups. Files transfer directly between devices with no upload or download from any server. What you send stays between you and the recipient.

Works in the background.
A lightweight foreground service keeps your device discoverable and connected even when you switch to another app. You'll get a notification when a message arrives.

Bilingual interface.
NearMe supports both English and Arabic with full right-to-left layout. Switch languages anytime from Settings  the entire app updates instantly.

Light and dark themes.
Choose between light mode, dark mode, or let the app follow your system setting.

How It Works Under the Hood

NearMe layers three wireless technologies on top of each other to handle discovery and data transfer.

Discovery happens through Bluetooth Low Energy (BLE). Every NearMe device continuously broadcasts a small packet containing its short ID and display name. Other devices pick this up and show the person in the discovery list. BLE works reliably within roughly 30 meters.

Extended discovery uses Wi-Fi Aware (also called Neighbor Awareness Networking) on devices that support it (Android 10+). This pushes the discovery range out to 50–100 meters, which is useful in larger spaces like campus grounds or event venues. Devices that don't support Wi-Fi Aware still work fine they just use BLE only.

Data transfer uses Google Nearby Connections for messaging and file transfer. When you tap on someone to chat, the app establishes a direct encrypted connection between the two devices. Under the hood, Nearby Connections upgrades from Bluetooth to Wi-Fi Direct automatically for better speed. On Wi-Fi Aware devices, data can also flow over NAN Data Path (NDP) for extended-range conversations.

Group chat uses a star topology. The group creator is the hub and every other member connects directly to the hub. When a member sends a message, it goes to the hub, which then fans it out to everyone else. This avoids the radio contention that would come from trying to maintain connections between every pair of devices.


Tech Stack

NearMe is built entirely in Kotlin with Jetpack Compose and Material 3 for the UI. Local message storage uses Room with KSP annotation processing. Asynchronous operations are handled with Kotlin Coroutines and Flow. Nearby device communication relies on Google Play Services Nearby Connections (play-services-nearby:19.1.0), the Android BLE APIs for discovery, and the Android Wi-Fi Aware APIs for extended range. Navigation uses Navigation Compose.

The app targets SDK 36 with a minimum of SDK 26 (Android 8.0), ensuring broad device compatibility while taking advantage of newer APIs where available.


Getting Started

Clone the repository and open it in Android Studio:

git clone https://github.com/hidayah-alrifae/NearMe.git

Sync Gradle, connect a physical Android device, and run. NearMe requires at least two physical devices to test any communication feature  BLE, Nearby Connections, and Wi-Fi Aware do not work on emulators.


Permissions

NearMe asks for Bluetooth and location permissions at first launch. The Bluetooth permissions are needed for BLE discovery and Nearby Connections. The location permission is an Android OS requirement for Bluetooth scanning  NearMe does not track, store, or transmit your location in any way. On Android 13+, a notification permission is requested so the app can alert you to new messages when it's in the background.


Security

Nearby Connections encrypts every connection with unique per-session keys generated during the handshake. No one  not even another NearMe user  can read traffic from a connection they're not part of. Wi-Fi Aware data paths are encrypted with AES-CCMP. BLE discovery broadcasts only your short ID and display name, never message content. All messages are stored locally on your device in a private database. Nothing is ever uploaded to any server.


Author

Hidaya Al-Rifae
University of Tripoli — Faculty of Information Technology
Mobile Computing Department — Class of 2026
