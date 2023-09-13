package org.vorpal.research.kex.asm.analysis.symgraph2.heapstate

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.*
import org.vorpal.research.kex.asm.analysis.symgraph2.objects.GraphVertex
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.reanimator.actionsequence.*
import org.vorpal.research.kex.reanimator.actionsequence.generator.ConstantGenerator
import org.vorpal.research.kex.reanimator.actionsequence.generator.Generator
import org.vorpal.research.kex.reanimator.actionsequence.generator.GeneratorContext
import org.vorpal.research.kex.smt.Result
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.log

class InvocationResultHeapState(
    objects: Collection<GraphVertex>,
    activeObjects: Set<GraphVertex>,
    predicateState: PredicateState,
    private val absCall: AbsCall,
    private val parentState: HeapState,
    private val termMappingToParent: Map<Term, Term>,
    private val activeObjectsMappingFromParent: Map<GraphVertex, GraphVertex>,
    private val returnValue: GraphVertex?
) : HeapState(objects, activeObjects, predicateState, termMappingToParent.keys) {
    override fun additionalToString(stateEnumeration: Map<HeapState, Int>): String = buildString {
        append(parentState.additionalToString(stateEnumeration))
        append(" -> ")
        append(stateEnumeration[this@InvocationResultHeapState])
        append("(")
        append(absCall.method)
        append(")")
    }

    override suspend fun restoreCalls(
        ctx: ExecutionContext,
        termValues: Map<Term, Descriptor>,
        argumentGenerator: Generator,
    ): RestorationResult {
//        log.debug("terms: $termValues and $terms")
        check(checkPredicateState(ctx, termValues) is Result.SatResult)
        val parentTermVals = termMappingToParent
            .filterValues { parentState.terms.contains(it) }
            .map { it.value to termValues.getValue(it.key) }
            .toMap()
//        log.debug("parent terms: $parentTermVals")
        val oldResult = parentState.restoreCalls(ctx, parentTermVals, argumentGenerator)
        val oldObjActions = oldResult.objectGenerators
        val methodCall = generateMethodCallSequence(termValues, argumentGenerator, oldObjActions, "v${hashCode()}")
        val newRootSequence = buildList {
            addAll(oldResult.rootSequence)
            add(methodCall)
        }
        val newObjActions = buildMap {
            putAll(oldObjActions.mapKeys { activeObjectsMappingFromParent.getValue(it.key) })
            returnValue?.let {
                put(it, methodCall)
            }
        }
        return RestorationResult(newObjActions, newRootSequence)
    }

    private fun generateMethodCallSequence(
        termValues: Map<Term, Descriptor>,
        actionSequenceGenerator: Generator,
        oldObjActions: Map<GraphVertex, ActionSequence>,
        name: String,
    ): ActionSequence {
        val method = absCall.method
        val arguments = absCall.arguments.map { arg ->
            when (arg) {
                is PrimitiveArgument -> {
                    val descriptor = termValues.getValue(arg.term)
                    actionSequenceGenerator.generate(descriptor)
                }

                is ObjectArgument -> {
                    oldObjActions.getValue(arg.obj)
                }

                else -> unreachable { log.debug("Descriptor is not primitive or object: $arg") }
            }
        }
        val methodCall = when {
            method.isConstructor -> {
                ConstructorCall(method, arguments)
            }

            method.isStatic -> {
                ExternalConstructorCall(method, arguments)
            }

            else -> {
                ExternalMethodCall(method, oldObjActions.getValue(absCall.thisArg), arguments)
            }
        }
        return ActionList(name, mutableListOf(methodCall))
    }
}
