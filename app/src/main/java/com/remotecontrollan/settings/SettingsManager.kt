package com.remotecontrollan.settings

import android.content.Context
import android.content.SharedPreferences
import com.remotecontrollan.model.AppMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("rc_settings", Context.MODE_PRIVATE)

    private val _appMode = MutableStateFlow(loadAppMode())
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    private fun loadAppMode(): AppMode {
        return when (prefs.getString("app_mode", null)) {
            "HOST" -> AppMode.HOST
            "CONTROLLER" -> AppMode.CONTROLLER
            else -> AppMode.UNSELECTED
        }
    }

    fun setAppMode(mode: AppMode) {
        prefs.edit().putString("app_mode", mode.name).apply()
        _appMode.value = mode
    }

    fun getStreamResolution(): Pair<Int, Int> {
        val w = prefs.getInt("stream_width", 720)
        val h = prefs.getInt("stream_height", 1600)
        return Pair(w, h)
    }

    fun setStreamResolution(width: Int, height: Int) {
        prefs.edit().putInt("stream_width", width).putInt("stream_height", height).apply()
    }

    fun getStreamFps(): Int = prefs.getInt("stream_fps", 30)

    fun setStreamFps(fps: Int) {
        prefs.edit().putInt("stream_fps", fps).apply()
    }
}
