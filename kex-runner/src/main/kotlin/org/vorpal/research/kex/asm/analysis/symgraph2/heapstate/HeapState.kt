package org.vorpal.research.kex.asm.analysis.symgraph2.heapstate

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.GraphPrimitive
import org.vorpal.research.kex.asm.analysis.symgraph2.GraphValue
import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphObject
import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphVertex
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.reanimator.actionsequence.generator.Generator
import org.vorpal.research.kex.smt.AsyncSMTProxySolver
import org.vorpal.research.kex.smt.FinalDescriptorReanimator
import org.vorpal.research.kex.smt.Result
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.emptyState
import org.vorpal.research.kex.state.predicate.state
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kthelper.logging.log

abstract class HeapState(
    val objects: Collection<GraphVertex>,
    val activeObjects: Set<GraphVertex>,
    val predicateState: PredicateState,
    val terms: Set<Term>,
) {

    private val stateToIndex = objects.withIndex().associate { (i, v) ->
        val id = if (v == GraphValue.Null) {
            "null"
        } else if (activeObjects.contains(v)) {
            "a$i"
        } else {
            "$i"
        }
        Pair(v, id)
    }

    fun getObjectIndex(obj: GraphVertex) = stateToIndex.getValue(obj)

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

    fun checkIsomorphism(other: HeapState): Map<GraphVertex, GraphVertex>? {
        if (objects.size != other.objects.size || activeObjects.size != other.activeObjects.size) {
            return null
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
        mapping: Map<GraphVertex, GraphVertex>,
        reverseMapping: Map<GraphVertex, GraphVertex>,
        activeDescriptors: Set<GraphVertex>,
        otherActiveDescriptors: Set<GraphVertex>,
    ): Map<GraphVertex, GraphVertex>? {
        if (activeDescriptors.all { mapping.containsKey(it) }) {
            check(otherActiveDescriptors.all { reverseMapping.containsKey(it) })
            return mapping
        }
        val obj = activeDescriptors.first { !mapping.containsKey(it) }
        for (mapTo in otherActiveDescriptors.filter { !reverseMapping.containsKey(it) }) {
            val newMapping = mapping.toMutableMap()
            val newReverseMapping = reverseMapping.toMutableMap()
            if (!tryAddMapping(newMapping, newReverseMapping, obj, mapTo, activeDescriptors, otherActiveDescriptors)) {
                continue
            }
            checkIsomorphismImpl(
                newMapping,
                newReverseMapping,
                activeDescriptors,
                otherActiveDescriptors
            )?.let { return it }
        }
        return null
    }

    private fun tryAddMapping(
        mapping: MutableMap<GraphVertex, GraphVertex>,
        reverseMapping: MutableMap<GraphVertex, GraphVertex>,
        obj: GraphVertex,
        mapTo: GraphVertex,
        activeDescriptors: Set<GraphVertex>,
        otherActiveDescriptors: Set<GraphVertex>,
    ): Boolean {
        if (obj.type != mapTo.type || (obj in activeDescriptors) != (mapTo in otherActiveDescriptors)) {
            return false
        }
        mapping[obj] = mapTo
        reverseMapping[mapTo] = obj
        when (obj) {
            is GraphObject -> {
                mapTo as GraphObject
                val mappedObjFields = obj.objectFields
                    .filterValues { it is GraphVertex }
                    .mapValues { it.value as GraphVertex }
                for ((field, value) in mappedObjFields) {
                    val otherValue =
                        mapTo.objectFields.getOrDefault(field, GraphValue.Null) as? GraphVertex ?: return false
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
        }
        return true
    }

//    private fun checkMapping(
//        a: List<GraphVertex>,
//        b: List<GraphVertex>,
//        other: HeapState,
//        indexA: Map<GraphVertex, Int>,
//        indexB: Map<GraphVertex, Int>,
//        p: IntArray
//    ): Boolean {
//        for ((i, x) in a.withIndex()) {
//            val y = b[p[i]]
//            if (x.type != y.type) {
//                return false
//            }
//            if ((x in activeObjects) != (y in other.activeObjects)) {
//                return false
//            }
//            if (x.type !is KexClass) {
//                continue
//            }
//            when (x) {
//                is GraphObject -> {
//                    y as GraphObject
//                    for ((field, value) in x.objectFields) {
//                        if (p[indexA.getValue(value)] != indexB[y.objectFields[field]]) {
//                            return false
//                        }
//                    }
//                }
//            }
//        }
//        return true
//    }

    private fun objectGraphToString() = buildString {
        appendLine("Nodes = ${objects.size}, active = ${activeObjects.size}")
        for (d in objects) {
            if (d.type !is KexClass) {
                continue
            }
            when (d) {
                is GraphObject -> {
                    for ((field, value) in d.objectFields.filterValues { it is GraphVertex }) {
                        val from = stateToIndex.getValue(d)
                        val to = stateToIndex.getValue(value as GraphVertex)
                        appendLine("$from.${field.first} -> $to")
                    }
                }
            }
        }
        for (obj in objects) {
            val objName = stateToIndex.getValue(obj) + "<$obj>"
            val objFields = when (obj) {
                is GraphObject -> {
                    obj.objectFields.filterValues { it is GraphPrimitive }.map { (field, value) ->
                        value as GraphPrimitive
                        ".${field.first} = ${value.term}"
                    }.joinToString(", ") + ", " + obj.objectFields.filterValues { it is GraphVertex }
                        .map { (field, value) ->
                            value as GraphVertex
                            ".${field.first} = ${stateToIndex.getValue(value)}"
                        }.joinToString(", ")
                }

                else -> ""
            }
            appendLine("  Object $objName { $objFields }")
        }
        appendLine("With state: \n $predicateState")
    }

    fun toString(stateEnumeration: Map<HeapState, Int>) = buildString {
        val hash = super.hashCode().toString(16)
        val index = stateEnumeration[this@HeapState]
        appendLine("[[${this@HeapState.javaClass.simpleName} #$index]] #$hash")
        appendLine(additionalToString(stateEnumeration))
        appendLine(objectGraphToString())
    }

    abstract fun additionalToString(stateEnumeration: Map<HeapState, Int>): String

    suspend fun restore(
        ctx: ExecutionContext,
        someTermValues: Map<Term, Descriptor>,
        argumentGenerator: Generator,
    ): RestorationResult? {
        val allTermValues = reanimateAllTerms(ctx, someTermValues) ?: return null
        return restoreCalls(ctx, allTermValues, argumentGenerator)
    }

    abstract suspend fun restoreCalls(
        ctx: ExecutionContext,
        termValues: Map<Term, Descriptor>,
        argumentGenerator: Generator
    ): RestorationResult

    suspend fun checkPredicateState(ctx: ExecutionContext, terms: Map<Term, Descriptor>): Result {
        var concretePredicateState = predicateState
        for ((term, desc) in terms) {
            concretePredicateState = concretePredicateState.addPredicate(state { term equality desc.term })
            concretePredicateState += desc.query
        }
        val result = AsyncSMTProxySolver(ctx).use {
            it.isPathPossibleAsync(concretePredicateState, emptyState())
        }
        check(result.known)
        return result
    }

    class RestorationResult(
        val objectGenerators: Map<GraphVertex, ActionSequence>,
        val rootSequence: List<ActionSequence>
    )

    suspend fun reanimateAllTerms(
        ctx: ExecutionContext,
        someTermValues: Map<Term, Descriptor>,
    ): Map<Term, Descriptor>? {
        val result = checkPredicateState(ctx, someTermValues)
        log.debug("Checking concrete mapping, result: ${result.javaClass.simpleName}")
        if (result !is Result.SatResult) {
            return null
        }
        val reanimator = FinalDescriptorReanimator(result.model, ctx)
        return terms.associateWith { reanimator.reanimate(it) }
    }

    fun makeReverseFieldMapping(mapping: Map<GraphVertex, GraphVertex>) = buildMap {
        for (obj1 in objects) {
            if (obj1 is GraphObject) {
                val obj2 = mapping.getValue(obj1) as GraphObject
//                for ((field, term2) in obj2.primitiveFields) {
//                    val term1 = obj1.primitiveFields[field] ?: continue
//                    put(term2, term1)
//                }
                for ((field, term2) in obj2.objectFields) {
                    if (term2 is GraphPrimitive) {
                        val term1 = obj1.objectFields[field] ?: continue
                        term1 as GraphPrimitive
                        put(term2.term, term1.term)
                    }
                }
            }
        }
    }
}
