package com.voluene.monobrowser.ui_tests

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.voluene.monobrowser.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The home screen widget's layout, drawn on a grey "wallpaper" at a typical 4x1 size. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetTest {
    private fun render(name: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = context.resources.displayMetrics.density
        // Through RemoteViews, like a launcher: only @RemoteView classes may be inflated (a plain View throws).
        val view = android.widget.RemoteViews(context.packageName, R.layout.widget_search).apply(context, android.widget.FrameLayout(context))
        val w = (360 * density).toInt()
        val h = (56 * density).toInt()
        val pad = (16 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF7A8A99.toInt()) // wallpaper
        canvas.translate(pad.toFloat(), pad.toFloat())
        view.draw(canvas)
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(sdk = [35], qualifiers = "xhdpi") fun light() = render("widget_light")
    @Test @Config(sdk = [35], qualifiers = "night-xhdpi") fun dark() = render("widget_dark")
}
