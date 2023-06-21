package org.vorpal.research.kex.asm.analysis.symgraph2.heapstate

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphObject
import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphVertex
import org.vorpal.research.kex.descriptor.ConstantDescriptor
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.descriptor.ObjectDescriptor
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.ktype.KexNull
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
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
        val id = if (v == GraphVertex.Null) {
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
                for ((field, value) in obj.objectFields) {
                    val otherValue = mapTo.objectFields.getOrDefault(field, GraphVertex.Null)
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

    private fun checkMapping(
        a: List<GraphVertex>,
        b: List<GraphVertex>,
        other: HeapState,
        indexA: Map<GraphVertex, Int>,
        indexB: Map<GraphVertex, Int>,
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
            when (x) {
                is GraphObject -> {
                    y as GraphObject
                    for ((field, value) in x.objectFields) {
                        if (p[indexA.getValue(value)] != indexB[y.objectFields[field]]) {
                            return false
                        }
                    }
                }
            }
        }
        return true
    }

    private fun objectGraphToString() = buildString {
        appendLine("Nodes = ${objects.size}, active = ${activeObjects.size}")
        for (d in objects) {
            if (d.type !is KexClass) {
                continue
            }
            when (d) {
                is GraphObject -> {
                    for ((_, value) in d.objectFields) {
                        val from = stateToIndex.getValue(d)
                        val to = stateToIndex.getValue(value)
                        appendLine("$from -> $to")
                    }
                }
            }
        }
        for (obj in objects) {
            val objName = stateToIndex.getValue(obj)
            val objFields = when (obj) {
                is GraphObject -> {
                    obj.primitiveFields.map { (field, value) ->
                        ".${field.first} = $value"
                    }.joinToString(", ")
                }

                else -> ""
            }
            appendLine("  Object $objName { $objFields }")
        }
        appendLine("With state: \n $predicateState")
    }

    fun toString(stateEnumeration: Map<HeapState, Int>) = buildString {
        appendLine("[[${this@HeapState.javaClass.simpleName} #${stateEnumeration[this@HeapState]}]]")
        appendLine(additionalToString(stateEnumeration))
        appendLine(objectGraphToString())
    }

    abstract fun additionalToString(stateEnumeration: Map<HeapState, Int>): String

    suspend fun getMappingToConcreteOrNull(
        ctx: ExecutionContext,
        objectDescriptors: Set<ObjectDescriptor>
    ): Pair<List<ActionSequence>, Map<ObjectDescriptor, ActionSequence>>? {
        if (objectDescriptors.size > activeObjects.size) {
            return null
        }
        val concreteMapper = ConcreteMapper(ctx, objectDescriptors)
        val mapping = concreteMapper.findMapping(emptyMap(), emptyMap()) ?: return null
        val result = restoreCalls(ctx, mapping.terms)
        val objectGenerators = objectDescriptors.associateWith { descriptor ->
            val graphObject = mapping.mapping.getValue(descriptor)
            val actionSequence = result.objectGenerators.getValue(graphObject)
            actionSequence
        }
        return result.rootSequence to objectGenerators
    }

    abstract suspend fun restoreCalls(ctx: ExecutionContext, termValues: Map<Term, Descriptor>): RestorationResult

    suspend fun checkPredicateState(ctx: ExecutionContext, terms: Map<Term, Descriptor>): Boolean {
        var concretePredicateState = predicateState
        for ((term, desc) in terms) {
            concretePredicateState = concretePredicateState.addPredicate(state { term equality desc.term })
            concretePredicateState += desc.query
        }
        val result = AsyncSMTProxySolver(ctx).use {
            it.isPathPossibleAsync(concretePredicateState, emptyState())
        }
        check(result.known)
        return result is Result.SatResult
    }

    class RestorationResult(
        val objectGenerators: Map<GraphVertex, ActionSequence>,
        val rootSequence: List<ActionSequence>
    )

    class ConcreteMapping(val mapping: Map<ObjectDescriptor, GraphObject>, val terms: Map<Term, Descriptor>)

    inner class ConcreteMapper(val ctx: ExecutionContext, val activeObjectDesc: Set<ObjectDescriptor>) {
        suspend fun findMapping(
            mapping: Map<ObjectDescriptor, GraphObject>,
            reverseMapping: Map<GraphObject, ObjectDescriptor>
        ): ConcreteMapping? {
            val descriptor = activeObjectDesc.firstOrNull { !mapping.containsKey(it) }
            if (descriptor == null) {
                var concretePredicateState = predicateState
                for ((desc, obj) in mapping) {
                    for ((field, value) in obj.primitiveFields) {
                        val concreteField = desc.fields[field] ?: continue
                        concreteField as ConstantDescriptor
                        concretePredicateState =
                            concretePredicateState.addPredicate(state { value equality concreteField.term })
                        concretePredicateState += concreteField.query
                    }
                }
                val result = AsyncSMTProxySolver(ctx).use {
                    it.isPathPossibleAsync(concretePredicateState, emptyState())
                }
                if (result !is Result.SatResult) {
                    return null
                }
                val reanimator = FinalDescriptorReanimator(result.model, ctx)
                val terms = terms.associateWith { reanimator.reanimate(it) }
//                println("Concrete Predicate State: $concretePredicateState")
                return ConcreteMapping(mapping, terms)
            }
            for (mapTo in activeObjects.filterIsInstance<GraphObject>().filter { !reverseMapping.containsKey(it) }) {
                val newMapping = mapping.toMutableMap()
                val newReverseMapping = reverseMapping.toMutableMap()
                if (!tryAddMapping(newMapping, newReverseMapping, descriptor, mapTo)) {
                    continue
                }
                findMapping(newMapping, newReverseMapping)?.let { return it }
            }
            return null
        }

        private fun tryAddMapping(
            mapping: MutableMap<ObjectDescriptor, GraphObject>,
            reverseMapping: MutableMap<GraphObject, ObjectDescriptor>,
            desc: ObjectDescriptor,
            mapTo: GraphObject,
        ): Boolean {
            if (desc.type != mapTo.type || (desc in activeObjectDesc) != (mapTo in activeObjects)) {
                return false
            }
            mapping[desc] = mapTo
            reverseMapping[mapTo] = desc
            for ((field, value) in desc.fields) {
                when (field.second) {
                    is KexNull, is KexClass -> {}
                    else -> continue
                }
                if (!mapTo.objectFields.containsKey(field)) {
                    log.debug("Not found, objectFields = ${mapTo.objectFields}")
                }
                val otherValue = mapTo.objectFields.getOrDefault(field, GraphVertex.Null)
                if (value == ConstantDescriptor.Null) {
                    if (otherValue.type !is KexNull) {
                        return false
                    }
                    continue
                }
                if (otherValue.type is KexNull) {
                    return false
                }
                value as ObjectDescriptor
                otherValue as GraphObject
                val map1 = mapping[value]
                val map2 = reverseMapping[otherValue]
                if (map1 == null && map2 == null) {
                    if (!tryAddMapping(mapping, reverseMapping, value, otherValue)) {
                        return false
                    }
                } else if (map1 != otherValue && map2 != value) {
                    return false
                }
            }
            return true
        }
    }
}
