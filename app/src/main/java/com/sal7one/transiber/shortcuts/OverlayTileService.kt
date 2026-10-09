package com.sal7one.transiber.shortcuts

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.i18n.uiText as localizedUiText

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sal7one.transiber.caption.CaptionCaptureService
import kotlinx.coroutines.*

/** Quick-settings tile for live captions, the only overlay Hearth now runs. */
class CaptionTileService : TileService() {
    private val uiText get() = this.localizedUiText()

    private val shortcut = OverlayShortcut.CAPTIONS
    private var listening: CoroutineScope? = null
    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch { CaptionCaptureService.running.collect { active ->
                qsTile?.apply {
                    label = uiText(UiR.string.tile_captions)
                    state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    contentDescription = "${uiText(UiR.string.tile_captions)} · ${if (active) uiText(UiR.string.service_tap_to_show_578b6) else uiText(UiR.string.service_tap_to_start_b11de)}"
                    if (Build.VERSION.SDK_INT >= 29) subtitle = if (active) uiText(UiR.string.service_tap_to_show_578b6) else uiText(UiR.string.service_tap_to_start_b11de)
                    updateTile()
                }
            } }
        }
    }
    override fun onStopListening() { listening?.cancel(); listening = null; super.onStopListening() }
    override fun onDestroy() { listening?.cancel(); super.onDestroy() }
    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { launch() } else launch()
    }
    private fun launch() {
        val intent = Intent(this, OverlayLaunchActivity::class.java)
            .putExtra(OverlayLaunchActivity.EXTRA, shortcut.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, shortcut.ordinal, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
