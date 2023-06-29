package org.vorpal.research.kex.state.transformer

import kotlinx.collections.immutable.toPersistentList
import org.vorpal.research.kex.asm.manager.MethodManager
import org.vorpal.research.kex.state.IncrementalPredicateState
import org.vorpal.research.kex.state.PredicateQuery
import org.vorpal.research.kex.state.predicate.CallPredicate
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.predicate.assume
import org.vorpal.research.kex.state.predicate.state
import org.vorpal.research.kex.state.term.CallTerm

object IntrinsicAdapter : Transformer<IntrinsicAdapter>, IncrementalTransformer {
    private val im = MethodManager.IntrinsicManager

    // todo
    override fun transformCallPredicate(predicate: CallPredicate): Predicate {
        val call = predicate.call as CallTerm
        return when (val method = call.method) {
            im.checkParameterIsNotNull(method.cm) -> assume { call.arguments[0] inequality null }
            im.checkNotNullParameter(method.cm) -> assume { call.arguments[0] inequality null }
            im.areEqual(method.cm) -> state { predicate.lhv equality (call.arguments[0] equls call.arguments[1]) }
            else -> predicate
        }
    }

    override fun apply(state: IncrementalPredicateState): IncrementalPredicateState {
        return IncrementalPredicateState(
            apply(state.state),
            state.queries
        )
    }
}
