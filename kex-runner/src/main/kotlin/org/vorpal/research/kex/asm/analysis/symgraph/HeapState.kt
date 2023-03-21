package org.vorpal.research.kex.asm.analysis.symgraph

import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.descriptor.ObjectDescriptor
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.ktype.KexNull

data class HeapState(val prevHeapState: HeapState?, val absCall: AbsCall?, val objects: Collection<GraphObject>, val activeObjects: Set<GraphObject>) {

    class PermutationGenerator(n: Int) {
        val p = IntArray(n) { it }

        fun nextPermutation(): Boolean {
            var i = p.size - 1
            while (i > 0 && p[i] < p[i - 1]) {
                i--
            }
            if (i == 0) {
                return false
            }
            // p[i] > p[i - 1]
            p.reverse(i, p.size)
            i--
            var j = i + 1
            while (p[j] < p[i]) j++
            val t = p[i];
            p[i] = p[j]
            p[j] = t
            return true
        }
    }

    fun checkIsomorphism(other: HeapState): Boolean {
        val a = objects.toList()
        val b = other.objects.toList()
        if (a.size != b.size) {
            return false
        }
        val indexA = a.map { it.descriptor }.withIndex().associate { (i, x) -> Pair(x, i) }
        val indexB = b.map { it.descriptor }.withIndex().associate { (i, x) -> Pair(x, i) }
        val permutations = PermutationGenerator(a.size)
        do {
            if (checkMapping(a, b, other, indexA, indexB, permutations.p)) {
                return true
            }
        } while (permutations.nextPermutation())
        return false
    }

    private fun checkMapping(
        a: List<GraphObject>,
        b: List<GraphObject>,
        other: HeapState,
        indexA: Map<Descriptor, Int>,
        indexB: Map<Descriptor, Int>,
        p: IntArray
    ): Boolean {
        for ((i, xObj) in a.withIndex()) {
            val x = xObj.descriptor
            val yObj = b[p[i]]
            val y = yObj.descriptor
            if (x.type != y.type) {
                return false
            }
            if ((xObj in activeObjects) != (yObj in other.activeObjects)) {
                return false
            }
            if (x.type !is KexClass) {
                continue
            }
            x as ObjectDescriptor
            y as ObjectDescriptor
            for ((field, fieldDescriptor) in x.fields) {
                val type = field.second
                if (type !is KexClass && type !is KexNull) {
                    continue
                }
                if (p[indexA.getValue(fieldDescriptor)] != indexB[y.fields[field]]) {
                    return false
                }
            }
        }
        return true
    }
}
