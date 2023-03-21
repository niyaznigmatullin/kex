package org.vorpal.research.kex.symgraph

import org.junit.Assert
import org.vorpal.research.kex.asm.analysis.symgraph.HeapState
import kotlin.test.Test
import kotlin.test.assertEquals

class PermutationGeneratorTest {

    @Test
    fun testPermutationsOfThree() {
        val all = arrayOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
        val g = HeapState.PermutationGenerator(3)
        for (i in 0 until 6) {
            Assert.assertArrayEquals(all[i], g.p)
            assertEquals((i != 5), g.nextPermutation())
        }
    }

    @Test
    fun testCount5() {
        val g = HeapState.PermutationGenerator(5)
        var count = 0
        do {
            count++
        } while (g.nextPermutation())
        assertEquals(count, 120)
    }
}
