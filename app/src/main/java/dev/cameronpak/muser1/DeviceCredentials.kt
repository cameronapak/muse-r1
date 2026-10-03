package dev.cameronpak.muser1

/** Never include this object in logs: its fields contain account credentials. */
class DeviceCredentials(
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String,
    val apiUrlV2: String = "https://api.muse.ai",
    val noiseHost: String = "hatch.metaaivm.com",
)

class DeviceIdentity(val mac: String) {
    val suffix = mac.replace(":", "").takeLast(6)
    val nodeId = "homelink-$suffix"
    val deviceId = "hatch-link:$mac"
    val bleName = "MuseGadget${suffix.uppercase()}"
}
