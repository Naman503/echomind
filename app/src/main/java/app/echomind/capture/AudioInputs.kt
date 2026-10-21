package app.echomind.capture

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Microphones the user can choose between, and how to route to them. */
object AudioInputs {
    data class Input(val id: Int, val kind: String, val label: String)

    fun isBluetooth(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    fun find(am: AudioManager, id: Int): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }

    private fun kind(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        else -> if (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) "bluetooth" else "other"
    }

    fun label(d: AudioDeviceInfo): String = when (kind(d)) {
        "phone" -> "Phone microphone"
        "bluetooth" -> d.productName?.toString()?.takeIf { it.isNotBlank() }?.let { "$it (Bluetooth)" } ?: "Bluetooth headset"
        "wired" -> "Wired headset"
        "usb" -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB microphone"
        else -> d.productName?.toString() ?: "Microphone"
    }

    /** One entry per physical microphone; phones list the built-in mic several times. */
    fun list(context: Context): List<Input> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val seen = HashSet<String>()
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { d ->
            val kind = kind(d)
            if (kind == "other") return@mapNotNull null
            val key = if (kind == "phone") "phone" else "$kind:${d.productName}"
            if (!seen.add(key)) return@mapNotNull null
            Input(if (kind == "phone") 0 else d.id, kind, label(d))
        }
    }
}
