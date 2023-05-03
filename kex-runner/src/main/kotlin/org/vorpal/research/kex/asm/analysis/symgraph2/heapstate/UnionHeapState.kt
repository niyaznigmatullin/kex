package org.vorpal.research.kex.asm.analysis.symgraph2.heapstate

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.GraphObject
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.term.Term

class UnionHeapState(
    predicateState: PredicateState,
    terms: Set<Term>,
    private val firstParentState: HeapState,
    private val secondParentState: HeapState,
    objMappingToSecondParent: Map<GraphObject, GraphObject>,
    private val termMappingToSecondParent: Map<Term, Term>,
) : HeapState(firstParentState.objects, firstParentState.activeObjects, predicateState, terms) {

    private val objMappingFromSecondParent = objMappingToSecondParent.map { it.value to it.key }.toMap()

    override fun additionalToString(stateEnumeration: Map<HeapState, Int>): String = buildString {
        append(firstParentState.additionalToString(stateEnumeration))
        append(" and ")
        append(stateEnumeration.getValue(secondParentState))
        append(" -> ")
        append(stateEnumeration.getValue(this@UnionHeapState))
    }

    override suspend fun restoreCalls(ctx: ExecutionContext, termValues: Map<Term, Descriptor>): RestorationResult {
        return if (!firstParentState.checkPredicateState(ctx, termValues)) {
            val secondMapping = termValues.mapKeys { termMappingToSecondParent.getOrDefault(it.key, it.key) }
            val secondTermValues = secondParentState.terms.associateWith { secondMapping.getValue(it) }
            check(secondParentState.checkPredicateState(ctx, secondTermValues))
            val result = secondParentState.restoreCalls(ctx, secondTermValues)
            val newObjGenerators = result.objectGenerators.mapKeys { objMappingFromSecondParent.getValue(it.key) }
            RestorationResult(newObjGenerators, result.rootSequence)
        } else {
            val terms = firstParentState.terms.associateWith { termValues.getValue(it) }
            firstParentState.restoreCalls(ctx, terms)
        }
    }
}
