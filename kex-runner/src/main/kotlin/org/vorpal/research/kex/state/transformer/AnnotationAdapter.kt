package org.vorpal.research.kex.state.transformer

import org.vorpal.research.kex.annotations.AnnotatedCall
import org.vorpal.research.kex.annotations.AnnotationsLoader
import org.vorpal.research.kex.state.BasicState
import org.vorpal.research.kex.state.IncrementalPredicateState
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.StateBuilder
import org.vorpal.research.kex.state.predicate.CallPredicate
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.term.CallTerm
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kthelper.collection.dequeOf
import ru.spbstu.wheels.mapToArray
import java.util.*

class AnnotationAdapter(
    val method: Method,
    val annotations: AnnotationsLoader
) : RecollectingTransformer<AnnotationAdapter>, IncrementalTransformer {
    override val builders = dequeOf(StateBuilder())

    private val Method.exactCall: AnnotatedCall?
        get() = annotations.getExactCall("$klass.$name", *argTypes.mapToArray { it.name })

    override fun apply(state: IncrementalPredicateState): IncrementalPredicateState {
        return IncrementalPredicateState(
            apply(state.state),
            state.queries
        )
    }

    override fun apply(ps: PredicateState): PredicateState {
        method.exactCall?.run {
            val (`this`, args) = collectArguments(ps)

            val states = StateBuilder()
            for ((i, param) in this.params.withIndex()) {
                for (annotation in param.annotations) {
                    val arg = args[i] ?: continue
                    annotation.preciseValue(arg)?.run { states += this }
                    states += annotation.preciseParam(arg, i) ?: continue
                }
            }
            for (annotation in this.annotations) {
                if (`this` != null) {
                    annotation.preciseValue(`this`)?.run { states += this }
                    states += annotation.preciseReturn(`this`) ?: continue
                }
            }
            currentBuilder += states.apply()
        }

        return super.apply(ps)
    }

    override fun transformCallPredicate(predicate: CallPredicate): Predicate {
        val call = predicate.call as CallTerm
        val method = call.method
        val args = call.arguments
        val annotatedCall = method.exactCall ?: return predicate
        val states = StateBuilder()
        for ((i, param) in annotatedCall.params.withIndex()) {
            for (annotation in param.annotations) {
                val arg = args[i]
                annotation.preciseValue(arg)?.run { states += this }
                states += annotation.preciseParam(args[i], i) ?: continue
            }
        }
        for (annotation in annotatedCall.annotations)
            states += annotation.preciseBeforeCall(predicate) ?: continue
        states += BasicState(Collections.singletonList(predicate))
        val returnValue = predicate.lhvUnsafe
        for (annotation in annotatedCall.annotations) {
            annotation.preciseAfterCall(predicate)?.run { states += this }
            if (returnValue != null) {
                annotation.preciseValue(returnValue)?.run { states += this }
                states += annotation.preciseReturn(returnValue) ?: continue
            }
        }
        currentBuilder += states.apply()
        return nothing()
    }
}
