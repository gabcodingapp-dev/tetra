package com.tetra.bot.vision

/**
 * Classification of pixel colors into tile exponents using the classic
 * gabrielecirulli 2048 palette.
 */
object Palette {
    data class Entry(val exp: Int, val r: Int, val g: Int, val b: Int)

    // (exponent, hex color) — the 2048 reference palette
    val ENTRIES: List<Entry> = listOf(
        Entry(0, 0xCD, 0xC1, 0xB4), // empty tile
        Entry(1, 0xEE, 0xE4, 0xDA), // 2
        Entry(2, 0xED, 0xE0, 0xC8), // 4
        Entry(3, 0xF2, 0xB1, 0x79), // 8
        Entry(4, 0xF5, 0x95, 0x63), // 16
        Entry(5, 0xF6, 0x7C, 0x5F), // 32
        Entry(6, 0xF6, 0x5E, 0x3B), // 64
        Entry(7, 0xED, 0xCF, 0x72), // 128
        Entry(8, 0xED, 0xCC, 0x61), // 256
        Entry(9, 0xED, 0xC8, 0x50), // 512
        Entry(10, 0xED, 0xC5, 0x3F), // 1024
        Entry(11, 0xED, 0xC2, 0x2E), // 2048
        Entry(12, 0x3C, 0x3A, 0x32), // 4096
        Entry(13, 0x2C, 0x2A, 0x28), // 8192
        Entry(14, 0x22, 0x21, 0x1E), // 16384
        Entry(15, 0x1A, 0x1A, 0x18)  // 32768
    )

    private val cache = HashMap<Int, Int>(256)

    /** returns the tile exponent (0 = empty) nearest to the given color */
    fun classify(r: Int, g: Int, b: Int): Int {
        val key = (r shl 16) or (g shl 8) or b
        cache[key]?.let { return it }
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (e in ENTRIES) {
            val dr = r - e.r
            val dg = g - e.g
            val db = b - e.b
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                best = e.exp
            }
        }
        cache[key] = best
        return best
    }
}