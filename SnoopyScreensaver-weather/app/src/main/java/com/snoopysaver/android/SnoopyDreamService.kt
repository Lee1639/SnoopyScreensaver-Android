package com.snoopysaver.android

import android.os.Build
import android.service.dreams.DreamService
import android.view.WindowManager

class SnoopyDreamService : DreamService() {
    private var session: SnoopySession? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setScreenBright(true)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
        setContentView(R.layout.screensaver)
        session = SnoopySession(
            context = this,
            root = findViewById(R.id.root),
            allowSetup = false,
            requestTree = {},
            requestAccess = {},
        )
        session?.start()
    }

    override fun onDetachedFromWindow() {
        session?.stop()
        session = null
        super.onDetachedFromWindow()
    }
}
