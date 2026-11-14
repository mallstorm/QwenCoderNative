package com.mallstorm.qwencoder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Original vector illustration: an architect sketching a software blueprint.
 * Drawn locally with Android Canvas, no network or image assets required.
 */
class DraftingRoomArtwork(context: Context) : View(context) {
    private fun ink(color: Int, line: Float = 0f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (line > 0f) {
            style = Paint.Style.STROKE
            strokeWidth = line
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    private fun shape(canvas: Canvas, color: Int, vararg points: Float) {
        if (points.size < 6 || points.size % 2 != 0) return
        val path = Path().apply {
            moveTo(points[0], points[1])
            var i = 2
            while (i < points.size) { lineTo(points[i], points[i + 1]); i += 2 }
            close()
        }
        canvas.drawPath(path, ink(color))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val saved = canvas.save()
        canvas.scale(width / 360f, height / 310f)

        // Midnight architectural studio with technical-grid texture.
        val backdrop = ink(Color.rgb(17, 28, 47)).apply {
            shader = LinearGradient(0f, 0f, 360f, 310f,
                intArrayOf(Color.rgb(25, 39, 66), Color.rgb(14, 23, 41)), null,
                Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(0f, 0f, 360f, 310f, 22f, 22f, backdrop)
        val clipping = Path().apply { addRoundRect(RectF(0f, 0f, 360f, 310f), 22f, 22f, Path.Direction.CW) }
        canvas.clipPath(clipping)
        val grid = ink(Color.argb(19, 127, 197, 233), 1f)
        for (x in 16..360 step 20) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 310f, grid)
        for (y in 10..310 step 20) canvas.drawLine(0f, y.toFloat(), 360f, y.toFloat(), grid)

        // Ambient desk-lamp halo and floating drafting annotations.
        canvas.drawCircle(261f, 140f, 100f, ink(Color.argb(16, 89, 212, 224)))
        canvas.drawCircle(261f, 140f, 65f, ink(Color.argb(12, 89, 212, 224)))
        canvas.drawLine(306f, 226f, 306f, 69f, ink(Color.rgb(78, 108, 144), 7f))
        canvas.drawLine(306f, 69f, 267f, 42f, ink(Color.rgb(92, 124, 163), 7f))
        shape(canvas, Color.rgb(70, 99, 137), 246f, 32f, 284f, 46f, 271f, 62f, 235f, 45f)
        canvas.drawCircle(255f, 48f, 5f, ink(Color.rgb(102, 232, 216)))
        canvas.drawLine(251f, 56f, 226f, 118f, ink(Color.argb(42, 100, 223, 225), 16f))
        val writing = ink(Color.argb(165, 99, 227, 215)).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        canvas.drawText("architecture / 01", 21f, 27f, writing)
        canvas.drawText("IDE    •    DESIGN", 229f, 295f, writing)

        // Large drawing table and slanted blueprint sheet.
        shape(canvas, Color.rgb(44, 59, 85), 68f, 251f, 317f, 225f, 348f, 270f, 100f, 302f)
        shape(canvas, Color.rgb(35, 56, 78), 93f, 206f, 298f, 173f, 332f, 261f, 129f, 288f)
        val paperOutline = Path().apply {
            moveTo(93f, 206f); lineTo(298f, 173f); lineTo(332f, 261f); lineTo(129f, 288f); close()
        }
        canvas.drawPath(paperOutline, ink(Color.rgb(71, 198, 190), 2.3f))
        // Card/grid modules: architect is planning a software interface.
        shape(canvas, Color.rgb(51, 109, 123), 165f, 225f, 241f, 210f, 256f, 249f, 181f, 263f)
        canvas.drawLine(173f, 234f, 246f, 221f, ink(Color.rgb(104, 218, 220), 2f))
        canvas.drawLine(178f, 242f, 215f, 235f, ink(Color.rgb(104, 218, 220), 2f))
        canvas.drawLine(182f, 249f, 226f, 241f, ink(Color.rgb(104, 218, 220), 2f))
        shape(canvas, Color.rgb(30, 83, 103), 267f, 197f, 290f, 192f, 300f, 216f, 275f, 221f)
        canvas.drawLine(274f, 204f, 289f, 201f, ink(Color.rgb(98, 220, 214), 1.8f))
        canvas.drawLine(278f, 211f, 293f, 208f, ink(Color.rgb(98, 220, 214), 1.5f))
        canvas.drawCircle(139f, 235f, 4f, ink(Color.rgb(97, 220, 214)))
        canvas.drawLine(144f, 235f, 159f, 232f, ink(Color.rgb(97, 220, 214), 1.8f))
        canvas.drawLine(139f, 244f, 162f, 239f, ink(Color.rgb(97, 220, 214), 1.3f))
        canvas.drawLine(125f, 264f, 145f, 260f, ink(Color.rgb(97, 220, 214), 2f))

        // Architect: shoulders, hair silhouette, face and extended drawing arm.
        shape(canvas, Color.rgb(53, 75, 112), 14f, 244f, 29f, 192f, 66f, 170f, 98f, 196f, 109f, 277f, 47f, 286f)
        shape(canvas, Color.rgb(75, 105, 146), 39f, 191f, 68f, 179f, 94f, 198f, 101f, 226f, 75f, 219f)
        canvas.drawOval(RectF(55f, 105f, 119f, 170f), ink(Color.rgb(211, 152, 124)))
        // Profile nose, jaw and neck.
        shape(canvas, Color.rgb(221, 161, 135), 91f, 139f, 125f, 146f, 113f, 159f, 100f, 159f, 96f, 184f, 75f, 173f)
        shape(canvas, Color.rgb(34, 42, 63), 51f, 137f, 56f, 111f, 83f, 97f, 111f, 111f, 116f, 136f, 98f, 130f, 90f, 117f, 78f, 130f, 65f, 152f, 54f, 153f)
        canvas.drawLine(88f, 142f, 101f, 144f, ink(Color.rgb(32, 48, 64), 2.1f))
        canvas.drawCircle(99f, 144f, 1.5f, ink(Color.rgb(27, 41, 57)))
        shape(canvas, Color.rgb(76, 106, 147), 78f, 187f, 110f, 203f, 184f, 231f, 174f, 248f, 96f, 231f)
        shape(canvas, Color.rgb(221, 158, 128), 174f, 231f, 191f, 234f, 201f, 245f, 194f, 249f, 172f, 244f)
        // Pen in the hand, meeting the interface blueprint.
        canvas.drawLine(195f, 241f, 215f, 228f, ink(Color.rgb(231, 196, 108), 3.6f))
        canvas.drawLine(215f, 228f, 221f, 225f, ink(Color.rgb(100, 225, 216), 2f))
        canvas.drawCircle(222f, 224f, 2.2f, ink(Color.rgb(129, 243, 220)))
        canvas.drawCircle(222f, 224f, 10f, ink(Color.argb(28, 129, 243, 220)))
        // Desk edge and construction labels.
        canvas.drawLine(101f, 302f, 348f, 269f, ink(Color.rgb(76, 104, 137), 4f))
        canvas.drawText("DRAFT  •  100%", 193f, 278f, ink(Color.argb(175, 117, 198, 214)).apply {
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 9f
        })
        canvas.restoreToCount(saved)
    }
}
