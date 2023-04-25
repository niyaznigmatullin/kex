package org.vorpal.research.kex.asm.analysis.symgraph2.heapstate

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.GraphObject
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.term.ValueTerm
import org.vorpal.research.kex.state.transformer.collectTerms

class UnionHeapState(
    predicateState: PredicateState,
    terms: Set<Term>,
    val firstParentState: HeapState,
    val secondParentState: HeapState,
    val objMappingToSecondParent: Map<GraphObject, GraphObject>,
    val termMappingToSecondParent: Map<Term, Term>,
) : HeapState(firstParentState.objects, firstParentState.activeObjects, predicateState, terms) {

    val objMappingFromSecondParent = objMappingToSecondParent.map { it.value to it.key }.toMap()

    override fun additionalToString(stateEnumeration: Map<HeapState, Int>): String = buildString {
        append(firstParentState.additionalToString(stateEnumeration))
        append(" and ")
        append(stateEnumeration.getValue(secondParentState))
        append(" -> ")
        append(stateEnumeration.getValue(this@UnionHeapState))
    }

    override fun restoreCalls(ctx: ExecutionContext, mapping: ConcreteMapping): RestorationResult {
        return if (!firstParentState.checkPredicateState(ctx, mapping.terms)) {
            val secondMapping = mapping.terms.mapKeys { termMappingToSecondParent.getOrDefault(it.key, it.key) }
            val terms = secondParentState.terms.associateWith { secondMapping.getValue(it) }
            check(secondParentState.checkPredicateState(ctx, terms))
            val result = secondParentState.restoreCalls(ctx, ConcreteMapping(mapping.mapping, terms))
            val newObjGenerators = result.objectGenerators.mapKeys { objMappingFromSecondParent.getValue(it.key) }
            RestorationResult(newObjGenerators, result.rootSequence)
        } else {
            val terms = firstParentState.terms.associateWith { mapping.terms.getValue(it) }
            firstParentState.restoreCalls(ctx, ConcreteMapping(mapping.mapping, terms))
        }
    }
}
