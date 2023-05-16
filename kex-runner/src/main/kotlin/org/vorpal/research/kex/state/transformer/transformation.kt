package org.vorpal.research.kex.state.transformer

import org.vorpal.research.kex.state.PredicateState

class Transformation : Transformer<Transformation> {
    private val transformers = mutableListOf<(PredicateState) -> PredicateState>()

    override fun apply(ps: PredicateState): PredicateState {
        var state = ps
        for (transformer in transformers) {
            state = transformer.invoke(state)
        }
        return state
    }

    operator fun Transformer<*>.unaryPlus() {
        transformers += { state -> this.apply(state) }
    }

    operator fun ((PredicateState) -> PredicateState).unaryPlus() {
        transformers += this
    }
}

fun transform(state: PredicateState, body: Transformation.() -> Unit): PredicateState {
    val transformer = Transformation()
    transformer.body()
    return transformer.apply(state)
}
