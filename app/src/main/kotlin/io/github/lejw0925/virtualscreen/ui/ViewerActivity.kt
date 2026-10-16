package io.github.lejw0925.virtualscreen.ui

import android.os.Bundle
import java.lang.ref.WeakReference

/** Separate PiP task; the full-screen host dismisses it before taking the surface. */
class ViewerActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        current = WeakReference(this)
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: WeakReference<ViewerActivity>? = null
        fun dismissPreview() { current?.get()?.dismissPreview() }
    }
}
