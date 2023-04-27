package org.vorpal.research.kex.asm.analysis.symgraph2.heapstate

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symgraph2.*
import org.vorpal.research.kex.asm.state.PredicateStateAnalysis
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.reanimator.actionsequence.*
import org.vorpal.research.kex.reanimator.actionsequence.generator.ConstantGenerator
import org.vorpal.research.kex.reanimator.actionsequence.generator.GeneratorContext
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kthelper.assert.unreachable

class InvocationResultHeapState(
    objects: Collection<GraphObject>,
    activeObjects: Set<GraphObject>,
    predicateState: PredicateState,
    private val absCall: AbsCall,
    private val parentState: HeapState,
    private val termMappingToParent: Map<Term, Term>,
    private val activeObjectsMappingFromParent: Map<GraphObject, GraphObject>,
    private val returnValue: GraphObject?
) : HeapState(objects, activeObjects, predicateState, termMappingToParent.keys) {
    override fun additionalToString(stateEnumeration: Map<HeapState, Int>): String = buildString {
        append(parentState.additionalToString(stateEnumeration))
        append(" -> ")
        append(stateEnumeration.getValue(this@InvocationResultHeapState))
        append("(")
        append(absCall.method)
        append(")")
    }

    override suspend fun restoreCalls(ctx: ExecutionContext, termValues: Map<Term, Descriptor>): RestorationResult {
        val actionSequenceGenerator = ConstantGenerator(GeneratorContext(ctx, PredicateStateAnalysis(ctx.cm)))
        check(checkPredicateState(ctx, termValues))
        val parentTermVals = termMappingToParent.map { it.value to termValues.getValue(it.key) }.toMap()
        val oldResult = parentState.restoreCalls(ctx, parentTermVals)
        val oldObjActions = oldResult.objectGenerators
        val methodCall = generateMethodCallSequence(termValues, actionSequenceGenerator, oldObjActions, "v${hashCode()}")
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
        termsConcretization: Map<Term, Descriptor>,
        actionSequenceGenerator: ConstantGenerator,
        oldObjActions: Map<GraphObject, ActionSequence>,
        name: String,
    ): ActionSequence {
        val method = absCall.method
        val arguments = absCall.arguments.map { arg ->
            when (arg) {
                is PrimitiveArgument -> {
                    val descriptor = termsConcretization.getValue(arg.term)
                    actionSequenceGenerator.generate(descriptor)
                }

                is ObjectArgument -> {
                    oldObjActions.getValue(arg.obj)
                }

                else -> unreachable { "wowowow" }
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
