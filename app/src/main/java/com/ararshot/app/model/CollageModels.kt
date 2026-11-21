package com.ararshot.app.model

import android.graphics.Bitmap

const val MAX_COLLAGE_PHOTOS = 9

enum class CollageAspectRatio(val label: String, val ratio: Float) {
    SQUARE("1:1", 1f),
    IG_FEED("4:5", 4f / 5f),
    STORY("9:16", 9f / 16f),
    PORTRAIT("3:4", 3f / 4f),
    LANDSCAPE("16:9", 16f / 9f)
}

enum class CollageBgColor(val label: String, val argb: Int) {
    DARK("Hitam", 0xFF0E1117.toInt()),
    WHITE("Putih", 0xFFFFFFFF.toInt()),
    CREAM("Krem", 0xFFF5F0E6.toInt()),
    SLATE("Abu", 0xFF1E2430.toInt()),
    NAVY("Biru Gelap", 0xFF0F172A.toInt()),
    PASTEL_BLUE("Biru Muda", 0xFFDBEAFE.toInt()),
    PASTEL_PINK("Merah Muda", 0xFFFCE7F3.toInt())
}

/**
 * Normalized [0..1] cell bounds inside the inner collage canvas.
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class CollageLayoutTemplate(
    val id: String,
    val name: String,
    val photoCount: Int,
    val cells: List<NormalizedRect>
)

/**
 * Per-cell flexible placement state:
 * - scale: zoom multiplier >= 1f on top of cover-fit
 * - panX / panY: normalized [-1f..1f] fraction of max available pan travel (100% resolution-independent)
 * - rotationDegrees: 0, 90, 180, or 270
 */
data class CollageCellState(
    val id: Long,
    val bitmap: Bitmap,
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val rotationDegrees: Int = 0
)

object CollageLayouts {

    private fun r(l: Float, t: Float, ri: Float, b: Float) = NormalizedRect(l, t, ri, b)

    private fun grid(cols: Int, rows: Int): List<NormalizedRect> {
        val list = ArrayList<NormalizedRect>(cols * rows)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                list.add(
                    r(
                        col.toFloat() / cols,
                        row.toFloat() / rows,
                        (col + 1).toFloat() / cols,
                        (row + 1).toFloat() / rows
                    )
                )
            }
        }
        return list
    }

    private fun rowSplit(rowCounts: List<Int>, rowWeights: List<Float>? = null): List<NormalizedRect> {
        val weights = rowWeights ?: List(rowCounts.size) { 1f }
        val totalW = weights.sum().coerceAtLeast(0.001f)
        val out = ArrayList<NormalizedRect>()
        var y = 0f
        rowCounts.forEachIndexed { idx, count ->
            val h = weights[idx] / totalW
            val yEnd = if (idx == rowCounts.lastIndex) 1f else y + h
            for (c in 0 until count) {
                out.add(r(c.toFloat() / count, y, (c + 1).toFloat() / count, yEnd))
            }
            y = yEnd
        }
        return out
    }

    private fun colSplit(colCounts: List<Int>, colWeights: List<Float>? = null): List<NormalizedRect> {
        val weights = colWeights ?: List(colCounts.size) { 1f }
        val totalW = weights.sum().coerceAtLeast(0.001f)
        val out = ArrayList<NormalizedRect>()
        var x = 0f
        colCounts.forEachIndexed { idx, count ->
            val w = weights[idx] / totalW
            val xEnd = if (idx == colCounts.lastIndex) 1f else x + w
            for (row in 0 until count) {
                out.add(r(x, row.toFloat() / count, xEnd, (row + 1).toFloat() / count))
            }
            x = xEnd
        }
        return out
    }

    fun templatesFor(count: Int): List<CollageLayoutTemplate> = when (count.coerceIn(1, MAX_COLLAGE_PHOTOS)) {
        1 -> listOf(
            CollageLayoutTemplate("1_full", "Penuh", 1, listOf(r(0f, 0f, 1f, 1f)))
        )
        2 -> listOf(
            CollageLayoutTemplate("2_lr", "Kiri-Kanan", 2, grid(2, 1)),
            CollageLayoutTemplate("2_tb", "Atas-Bawah", 2, grid(1, 2)),
            CollageLayoutTemplate("2_hero_l", "Fokus Kiri", 2, colSplit(listOf(1, 1), listOf(0.64f, 0.36f))),
            CollageLayoutTemplate("2_hero_t", "Fokus Atas", 2, rowSplit(listOf(1, 1), listOf(0.64f, 0.36f)))
        )
        3 -> listOf(
            CollageLayoutTemplate("3_hero_t", "Fokus Atas", 3, rowSplit(listOf(1, 2), listOf(0.56f, 0.44f))),
            CollageLayoutTemplate("3_hero_l", "Fokus Kiri", 3, colSplit(listOf(1, 2), listOf(0.56f, 0.44f))),
            CollageLayoutTemplate("3_hero_r", "Fokus Kanan", 3, colSplit(listOf(2, 1), listOf(0.44f, 0.56f))),
            CollageLayoutTemplate("3_cols", "3 Kolom", 3, grid(3, 1)),
            CollageLayoutTemplate("3_rows", "3 Baris", 3, grid(1, 3))
        )
        4 -> listOf(
            CollageLayoutTemplate("4_grid", "Grid 2×2", 4, grid(2, 2)),
            CollageLayoutTemplate("4_hero_t", "Fokus Atas", 4, rowSplit(listOf(1, 3), listOf(0.60f, 0.40f))),
            CollageLayoutTemplate("4_hero_l", "Fokus Kiri", 4, colSplit(listOf(1, 3), listOf(0.58f, 0.42f))),
            CollageLayoutTemplate(
                "4_magazine", "Majalah", 4,
                listOf(
                    r(0f, 0f, 0.62f, 0.55f),
                    r(0.62f, 0f, 1f, 0.55f),
                    r(0f, 0.55f, 0.38f, 1f),
                    r(0.38f, 0.55f, 1f, 1f)
                )
            ),
            CollageLayoutTemplate("4_cols", "4 Kolom", 4, grid(4, 1))
        )
        5 -> listOf(
            CollageLayoutTemplate("5_2_3", "2 + 3", 5, rowSplit(listOf(2, 3), listOf(0.52f, 0.48f))),
            CollageLayoutTemplate("5_3_2", "3 + 2", 5, rowSplit(listOf(3, 2), listOf(0.48f, 0.52f))),
            CollageLayoutTemplate(
                "5_hero_l", "Fokus Kiri", 5,
                listOf(
                    r(0f, 0f, 0.52f, 1f),
                    r(0.52f, 0f, 0.76f, 0.5f),
                    r(0.76f, 0f, 1f, 0.5f),
                    r(0.52f, 0.5f, 0.76f, 1f),
                    r(0.76f, 0.5f, 1f, 1f)
                )
            ),
            CollageLayoutTemplate(
                "5_hero_t", "Fokus Atas", 5,
                listOf(
                    r(0f, 0f, 1f, 0.52f),
                    r(0f, 0.52f, 0.5f, 0.76f),
                    r(0.5f, 0.52f, 1f, 0.76f),
                    r(0f, 0.76f, 0.5f, 1f),
                    r(0.5f, 0.76f, 1f, 1f)
                )
            ),
            CollageLayoutTemplate(
                "5_bento", "Bento", 5,
                listOf(
                    r(0f, 0f, 0.55f, 0.6f),
                    r(0.55f, 0f, 1f, 0.35f),
                    r(0.55f, 0.35f, 1f, 0.6f),
                    r(0f, 0.6f, 0.45f, 1f),
                    r(0.45f, 0.6f, 1f, 1f)
                )
            )
        )
        6 -> listOf(
            CollageLayoutTemplate("6_3x2", "Grid 3×2", 6, grid(3, 2)),
            CollageLayoutTemplate("6_2x3", "Grid 2×3", 6, grid(2, 3)),
            CollageLayoutTemplate(
                "6_hero_tl", "Fokus Utama", 6,
                listOf(
                    r(0f, 0f, 0.667f, 0.667f),
                    r(0.667f, 0f, 1f, 0.333f),
                    r(0.667f, 0.333f, 1f, 0.667f),
                    r(0f, 0.667f, 0.333f, 1f),
                    r(0.333f, 0.667f, 0.667f, 1f),
                    r(0.667f, 0.667f, 1f, 1f)
                )
            ),
            CollageLayoutTemplate("6_1_2_3", "Piramida", 6, rowSplit(listOf(1, 2, 3), listOf(0.40f, 0.32f, 0.28f)))
        )
        7 -> listOf(
            CollageLayoutTemplate("7_2_3_2", "2 + 3 + 2", 7, rowSplit(listOf(2, 3, 2))),
            CollageLayoutTemplate("7_1_3_3", "Fokus Atas", 7, rowSplit(listOf(1, 3, 3), listOf(0.42f, 0.29f, 0.29f))),
            CollageLayoutTemplate("7_3_2_2", "3 + 2 + 2", 7, rowSplit(listOf(3, 2, 2))),
            CollageLayoutTemplate(
                "7_bento", "Bento 7", 7,
                listOf(
                    r(0f, 0f, 0.5f, 0.5f),
                    r(0.5f, 0f, 1f, 0.25f),
                    r(0.5f, 0.25f, 1f, 0.5f),
                    r(0f, 0.5f, 0.5f, 0.75f),
                    r(0.5f, 0.5f, 1f, 0.75f),
                    r(0f, 0.75f, 0.5f, 1f),
                    r(0.5f, 0.75f, 1f, 1f)
                )
            )
        )
        8 -> listOf(
            CollageLayoutTemplate("8_4x2", "Grid 4×2", 8, grid(4, 2)),
            CollageLayoutTemplate("8_2x4", "Grid 2×4", 8, grid(2, 4)),
            CollageLayoutTemplate("8_2_3_3", "2 + 3 + 3", 8, rowSplit(listOf(2, 3, 3), listOf(0.40f, 0.30f, 0.30f))),
            CollageLayoutTemplate("8_3_2_3", "3 + 2 + 3", 8, rowSplit(listOf(3, 2, 3)))
        )
        else -> listOf(
            CollageLayoutTemplate("9_3x3", "Grid 3×3", 9, grid(3, 3)),
            CollageLayoutTemplate("9_1_4_4", "Fokus Atas", 9, rowSplit(listOf(1, 4, 4), listOf(0.44f, 0.28f, 0.28f))),
            CollageLayoutTemplate("9_2_3_4", "Bertingkat", 9, rowSplit(listOf(2, 3, 4), listOf(0.38f, 0.33f, 0.29f))),
            CollageLayoutTemplate("9_3_3_3_hero", "Fokus Tengah", 9, rowSplit(listOf(3, 3, 3), listOf(0.28f, 0.44f, 0.28f)))
        )
    }
}
