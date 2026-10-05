package com.remotecontrollan.host

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.remotecontrollan.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClipboardSync(private val context: Context) {

    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /**
     * Reads current Host clipboard text on explicit user request.
     */
    suspend fun getClipboardText(): String = withContext(Dispatchers.Main) {
        try {
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                AppLogger.i("ClipboardSync", "Retrieved host clipboard (length: ${text.length})")
                return@withContext text
            }
        } catch (e: Exception) {
            AppLogger.w("ClipboardSync", "Failed to read host clipboard: ${e.message}")
        }
        return@withContext ""
    }

    /**
     * Sets Host clipboard text sent by Controller on explicit user request.
     */
    suspend fun setClipboardText(text: String): Boolean = withContext(Dispatchers.Main) {
        return@withContext try {
            val clip = ClipData.newPlainText("RemoteControl LAN", text)
            clipboard?.setPrimaryClip(clip)
            AppLogger.i("ClipboardSync", "Set host clipboard (length: ${text.length})")
            true
        } catch (e: Exception) {
            AppLogger.e("ClipboardSync", "Failed to set host clipboard: ${e.message}")
            false
        }
    }
}
