package app.materialnonogram.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pinned vectors: Sudoku's (06-DETERMINISM §3.1) and `NonoDet vectors` (03 §3.1), JVM and Pixel 10 alike. */
class RngTest {
    private fun hex(v: Long) = java.lang.Long.toHexString(v).padStart(16, '0')

    @Test fun splitMixFromZero() {
        val r = SplitMix64(0)
        assertEquals("e220a8397b1dcdaf 6e789e6aa1b965f4 06c45d188009454f f88bb8a8724c81ec", List(4) { hex(r.next()) }.joinToString(" "))
    }

    @Test fun splitMixFrom1234567() {
        val r = SplitMix64(1234567)
        assertEquals("599ed017fb08fc85 2c73f08458540fa5 883ebce5a3f27c77 3fbef740e9177b3f", List(4) { hex(r.next()) }.joinToString(" "))
    }

    @Test fun boundedInts() {
        val r = SplitMix64(42)
        assertEquals("6 1 2 3 0 7 1 7 3 5 1 4 4 4 5 1 0 4 0 6", List(20) { r.nextInt(9) }.joinToString(" "))
    }

    @Test fun sudokuSubseedStillMatches() {
        assertEquals(657414060717504974L, SplitMix64.subseed(42, 0x5355444F4B5539L, 0))
    }

    @Test fun nonogramSubseeds() {
        val d = Generator.domain(1, 15, 15, Tier.HARD)
        assertEquals("daf1014657698c5c fd3a8af6b24557a8 ad3f7d0cb6330205", List(3) { hex(SplitMix64.subseed(0, d, it)) }.joinToString(" "))
    }
}
