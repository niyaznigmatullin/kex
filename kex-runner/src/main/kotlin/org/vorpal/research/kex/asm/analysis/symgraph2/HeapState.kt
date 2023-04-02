package org.vorpal.research.kex.asm.analysis.symgraph2

import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.state.PredicateState

data class HeapState(
    val prevHeapState: HeapState?,
    val absCall: AbsCall?,
    val objects: Collection<GraphObject>,
    val activeObjects: Set<GraphObject>,
    val predicateState: PredicateState,
) {

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
        if (objects.size != other.objects.size || activeObjects.size != other.activeObjects.size) {
            return false
        }
        val result = checkIsomorphismImpl(
            mapOf(),
            mapOf(),
            activeObjects.toSet(),
            other.activeObjects.toSet()
        )
//        if (result) {
//            println("this two are equal ===============")
//            println(GraphBuilder.stateToString(this.hashCode(), this))
//            println(GraphBuilder.stateToString(other.hashCode(), other))
//            println("==================")
//        }
        return result
//        val a = objects.toList()
//        val b = other.objects.toList()
//        if (a.size != b.size || activeObjects.size != other.activeObjects.size) {
//            return false
//        }
//        val indexA = a.map { it.descriptor }.withIndex().associate { (i, x) -> Pair(x, i) }
//        val indexB = b.map { it.descriptor }.withIndex().associate { (i, x) -> Pair(x, i) }
//
//        val permutations = PermutationGenerator(a.size)
//        do {
//            if (checkMapping(a, b, other, indexA, indexB, permutations.p)) {
//                return true
//            }
//        } while (permutations.nextPermutation())
//        return false
    }

    private fun checkIsomorphismImpl(
        mapping: Map<GraphObject, GraphObject>,
        reverseMapping: Map<GraphObject, GraphObject>,
        activeDescriptors: Set<GraphObject>,
        otherActiveDescriptors: Set<GraphObject>,
    ): Boolean {
        if (activeDescriptors.all { mapping.containsKey(it) }) {
            return otherActiveDescriptors.all { reverseMapping.containsKey(it) }
//            check(otherActiveDescriptors.all { reverseMapping.containsKey(it) })
//            return true
        }
        val obj = activeDescriptors.first { !mapping.containsKey(it) }
        for (mapTo in otherActiveDescriptors.filter { !reverseMapping.containsKey(it) }) {
            val newMapping = mapping.toMutableMap()
            val newReverseMapping = reverseMapping.toMutableMap()
            if (!tryAddMapping(newMapping, newReverseMapping, obj, mapTo, activeDescriptors, otherActiveDescriptors)) {
                continue
            }
            if (checkIsomorphismImpl(newMapping, newReverseMapping, activeDescriptors, otherActiveDescriptors)) {
                return true
            }
        }
        return false
    }

    private fun tryAddMapping(
        mapping: MutableMap<GraphObject, GraphObject>,
        reverseMapping: MutableMap<GraphObject, GraphObject>,
        obj: GraphObject,
        mapTo: GraphObject,
        activeDescriptors: Set<GraphObject>,
        otherActiveDescriptors: Set<GraphObject>,
    ): Boolean {
        if (obj.type != mapTo.type || (obj in activeDescriptors) != (mapTo in otherActiveDescriptors)) {
            return false
        }
        mapping[obj] = mapTo
        reverseMapping[mapTo] = obj
        if (obj != GraphObject.Null) {
            for ((field, value) in obj.objectFields) {
                val otherValue = mapTo.objectFields.getValue(field)
                val map1 = mapping[value]
                val map2 = reverseMapping[otherValue]
                if (map1 == null && map2 == null) {
                    if (!tryAddMapping(
                            mapping,
                            reverseMapping,
                            value,
                            otherValue,
                            activeDescriptors,
                            otherActiveDescriptors
                        )
                    ) {
                        return false
                    }
                } else if (map1 != otherValue && map2 != value) {
                    return false
                }
            }
        }
        return true
    }

    private fun checkMapping(
        a: List<GraphObject>,
        b: List<GraphObject>,
        other: HeapState,
        indexA: Map<GraphObject, Int>,
        indexB: Map<GraphObject, Int>,
        p: IntArray
    ): Boolean {
        for ((i, x) in a.withIndex()) {
            val y = b[p[i]]
            if (x.type != y.type) {
                return false
            }
            if ((x in activeObjects) != (y in other.activeObjects)) {
                return false
            }
            if (x.type !is KexClass) {
                continue
            }
            for ((field, value) in x.objectFields) {
                if (p[indexA.getValue(value)] != indexB[y.objectFields[field]]) {
                    return false
                }
            }
        }
        return true
    }
}
