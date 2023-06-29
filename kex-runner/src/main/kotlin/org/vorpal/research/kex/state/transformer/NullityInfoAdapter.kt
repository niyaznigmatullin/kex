package org.vorpal.research.kex.state.transformer

import org.vorpal.research.kex.state.ChoiceState
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.StateBuilder
import org.vorpal.research.kex.state.predicate.assume
import org.vorpal.research.kex.state.term.ArrayIndexTerm
import org.vorpal.research.kex.state.term.ArrayLengthTerm
import org.vorpal.research.kex.state.term.CallTerm
import org.vorpal.research.kex.state.term.ConstClassTerm
import org.vorpal.research.kex.state.term.FieldTerm
import org.vorpal.research.kex.state.term.StaticClassRefTerm
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kthelper.collection.dequeOf

class NullityInfoAdapter : RecollectingTransformer<NullityInfoAdapter> {
    override val builders = dequeOf(StateBuilder())
    private var annotatedTerms = setOf<Term>()

    override fun transformChoice(ps: ChoiceState): PredicateState {
        val oldAnnotatedTerms = annotatedTerms.toSet()
        val newChoices = arrayListOf<PredicateState>()
        val choiceAnnotatedTerms = arrayListOf<Set<Term>>()
        for (choice in ps) {
            builders.add(StateBuilder())
            annotatedTerms = oldAnnotatedTerms.toSet()

            super.transformBase(choice)

            newChoices.add(currentBuilder.apply())
            choiceAnnotatedTerms.add(annotatedTerms.toSet())
            builders.pollLast()
        }
        currentBuilder += newChoices
        annotatedTerms = choiceAnnotatedTerms.flatten().toSet()
                .filterTo(mutableSetOf()) { term -> choiceAnnotatedTerms.all { term in it } }
        return ps
    }

    override fun transformFieldTerm(term: FieldTerm): Term {
        if (!term.isStatic && term.owner !in annotatedTerms) {
            currentBuilder += assume { term.owner inequality null }
            annotatedTerms = annotatedTerms + term.owner
        }
        return super.transformFieldTerm(term)
    }

    override fun transformArrayIndexTerm(term: ArrayIndexTerm): Term {
        if (term.arrayRef !in annotatedTerms) {
            currentBuilder += assume { term.arrayRef inequality null }
            annotatedTerms = annotatedTerms + term.arrayRef
        }
        return super.transformArrayIndexTerm(term)
    }

    override fun transformArrayLengthTerm(term: ArrayLengthTerm): Term {
        if (term.arrayRef !in annotatedTerms) {
            currentBuilder += assume { term.arrayRef inequality null }
            annotatedTerms = annotatedTerms + term.arrayRef
        }
        return super.transformArrayLengthTerm(term)
    }

    override fun transformConstClassTerm(term: ConstClassTerm): Term {
        if (term !in annotatedTerms) {
            currentBuilder += assume { term inequality null }
            annotatedTerms = annotatedTerms + term
        }
        return super.transformConstClassTerm(term)
    }

    override fun transformStaticClassRefTerm(term: StaticClassRefTerm): Term {
        if (term !in annotatedTerms) {
            currentBuilder += assume { term inequality null }
            annotatedTerms = annotatedTerms + term
        }
        return super.transformStaticClassRefTerm(term)
    }

    override fun transformCallTerm(term: CallTerm): Term {
        if (!term.isStatic && term.owner !in annotatedTerms) {
            currentBuilder += assume { term.owner inequality null }
            annotatedTerms = annotatedTerms + term.owner
        }
        return super.transformCallTerm(term)
    }
}
