package com.neurolens.eyecare.ui.components

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

/** A small line-icon set (24dp grid, 1.8 stroke, round caps) so every icon shares one visual language. */
object EyeIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy); arcTo(r, r, 0f, true, true, cx - r, cy); arcTo(r, r, 0f, true, true, cx + r, cy); close()
    }

    val Home = icon("home") {
        moveTo(4f, 10.5f); lineTo(12f, 4f); lineTo(20f, 10.5f)
        moveTo(6f, 9f); lineTo(6f, 19f); quadTo(6f, 20f, 7f, 20f); lineTo(17f, 20f); quadTo(18f, 20f, 18f, 19f); lineTo(18f, 9f)
        moveTo(10f, 20f); lineTo(10f, 14.5f); lineTo(14f, 14.5f); lineTo(14f, 20f)
    }
    val Chart = icon("chart") {
        moveTo(4f, 20f); lineTo(20f, 20f)
        moveTo(7f, 16f); lineTo(7f, 12f); moveTo(12f, 16f); lineTo(12f, 6f); moveTo(17f, 16f); lineTo(17f, 9.5f)
    }
    val Settings = icon("settings") {
        circle(12f, 12f, 3f)
        moveTo(12f, 3.5f); lineTo(12f, 5.5f); moveTo(12f, 18.5f); lineTo(12f, 20.5f)
        moveTo(3.5f, 12f); lineTo(5.5f, 12f); moveTo(18.5f, 12f); lineTo(20.5f, 12f)
        moveTo(6f, 6f); lineTo(7.4f, 7.4f); moveTo(16.6f, 16.6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(16.6f, 7.4f); moveTo(7.4f, 16.6f); lineTo(6f, 18f)
    }
    val Eye = icon("eye") {
        moveTo(2.8f, 12f); quadTo(12f, 3f, 21.2f, 12f); quadTo(12f, 21f, 2.8f, 12f); close()
        circle(12f, 12f, 3.2f)
    }
    val Sun = icon("sun") {
        circle(12f, 12f, 4f)
        moveTo(12f, 2.5f); lineTo(12f, 4.5f); moveTo(12f, 19.5f); lineTo(12f, 21.5f)
        moveTo(2.5f, 12f); lineTo(4.5f, 12f); moveTo(19.5f, 12f); lineTo(21.5f, 12f)
        moveTo(5.3f, 5.3f); lineTo(6.7f, 6.7f); moveTo(17.3f, 17.3f); lineTo(18.7f, 18.7f)
        moveTo(18.7f, 5.3f); lineTo(17.3f, 6.7f); moveTo(6.7f, 17.3f); lineTo(5.3f, 18.7f)
    }
    val Moon = icon("moon") {
        moveTo(19f, 14.5f); quadTo(12f, 17f, 9f, 12f); quadTo(7f, 7f, 10.5f, 4f)
        quadTo(4f, 5.5f, 4.5f, 12.5f); quadTo(6f, 20f, 13f, 19.5f); quadTo(17f, 19f, 19f, 14.5f); close()
    }
    val Distance = icon("distance") {
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(6.5f, 8.5f); lineTo(3f, 12f); lineTo(6.5f, 15.5f)
        moveTo(17.5f, 8.5f); lineTo(21f, 12f); lineTo(17.5f, 15.5f)
    }
    val Clock = icon("clock") {
        circle(12f, 12f, 8.5f); moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }
    val Blink = icon("blink") {
        moveTo(3f, 10f); quadTo(12f, 17f, 21f, 10f)
        moveTo(6f, 13.2f); lineTo(4.8f, 15.5f); moveTo(12f, 14.8f); lineTo(12f, 17.5f); moveTo(18f, 13.2f); lineTo(19.2f, 15.5f)
    }
    val Leaf = icon("leaf") {
        moveTo(5f, 19f); quadTo(4f, 6f, 19f, 5f); quadTo(19f, 19f, 5f, 19f); close()
        moveTo(5f, 19f); lineTo(13f, 11f)
    }
    val Play = icon("play") { moveTo(8f, 5.5f); lineTo(18.5f, 12f); lineTo(8f, 18.5f); close() }
    val Stop = icon("stop") {
        moveTo(7.5f, 6.5f); lineTo(16.5f, 6.5f); quadTo(17.5f, 6.5f, 17.5f, 7.5f); lineTo(17.5f, 16.5f)
        quadTo(17.5f, 17.5f, 16.5f, 17.5f); lineTo(7.5f, 17.5f); quadTo(6.5f, 17.5f, 6.5f, 16.5f)
        lineTo(6.5f, 7.5f); quadTo(6.5f, 6.5f, 7.5f, 6.5f); close()
    }
    val Bell = icon("bell") {
        moveTo(6f, 16.5f); lineTo(6f, 11f); quadTo(6f, 5f, 12f, 5f); quadTo(18f, 5f, 18f, 11f); lineTo(18f, 16.5f)
        moveTo(4.5f, 16.5f); lineTo(19.5f, 16.5f); moveTo(10f, 19.5f); quadTo(12f, 21f, 14f, 19.5f)
    }
    val Chevron = icon("chevron") { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) }
    val Back = icon("back") { moveTo(14.5f, 6f); lineTo(8.5f, 12f); lineTo(14.5f, 18f) }
    val Share = icon("share") {
        moveTo(12f, 4f); lineTo(12f, 15f); moveTo(8f, 8f); lineTo(12f, 4f); lineTo(16f, 8f)
        moveTo(5f, 13f); lineTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 13f)
    }
    val Info = icon("info") { circle(12f, 12f, 8.5f); moveTo(12f, 11f); lineTo(12f, 16f); moveTo(12f, 8f); lineTo(12f, 8.1f) }
}
