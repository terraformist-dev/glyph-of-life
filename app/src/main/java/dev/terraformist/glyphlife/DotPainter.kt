package dev.terraformist.glyphlife

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Paints brightness levels (0..255) as round dots, like the Glyph Matrix: white on black, faint unlit dots. */
object DotPainter {
    private const val GHOST = 0x23
    private const val LIT = 0xF4

    fun paint(levels: IntArray, pitch: Int): Bitmap =
        Bitmap.createBitmap(Disc.N * pitch, Disc.N * pitch, Bitmap.Config.ARGB_8888).also { paintInto(it, levels) }

    /** Repaint an existing bitmap (made by [paint]), so the app's animation doesn't make a new one every frame. */
    fun paintInto(bmp: Bitmap, levels: IntArray) {
        val n = Disc.N
        val pitch = bmp.width / n
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = pitch * 0.40f
        for (i in 0 until Disc.CELLS) {
            if (!Disc.inside[i]) continue
            val v = GHOST + (LIT - GHOST) * levels[i] / 255
            paint.color = Color.rgb(v, v, v)
            canvas.drawCircle((i % n) * pitch + pitch / 2f, (i / n) * pitch + pitch / 2f, radius, paint)
        }
    }
}
