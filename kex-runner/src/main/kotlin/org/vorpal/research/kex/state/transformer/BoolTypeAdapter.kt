package org.vorpal.research.kex.state.transformer

import org.vorpal.research.kex.ktype.*
import org.vorpal.research.kex.state.predicate.EqualityPredicate
import org.vorpal.research.kex.state.predicate.FieldStorePredicate
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.predicate.predicate
import org.vorpal.research.kex.state.term.*
import org.vorpal.research.kfg.ir.value.instruction.BinaryOpcode
import org.vorpal.research.kfg.type.TypeFactory
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.log

class BoolTypeAdapter(val types: TypeFactory) : Transformer<BoolTypeAdapter> {
    override fun transformEqualityPredicate(predicate: EqualityPredicate): Predicate {
        val lhv = predicate.lhv
        val rhv = predicate.rhv
        val type = predicate.type
        val loc = predicate.location
        return when {
            lhv.type is KexBool && rhv.type is KexInt -> predicate(type, loc) { lhv equality (rhv `as` KexBool) }
            lhv.type is KexInt && rhv.type is KexBool -> predicate(type, loc) { lhv equality (rhv `as` KexInt) }
            else -> predicate
        }
    }

    override fun transformFieldStorePredicate(predicate: FieldStorePredicate): Predicate {
        val lhv = predicate.field
        val rhv = predicate.value
        val type = predicate.type
        val loc = predicate.location
        val lhvType = (lhv.type as KexReference).reference
        return when {
            lhvType is KexBool && rhv.type is KexInt -> predicate(type, loc) { lhv.store(rhv `as` KexBool) }
            lhvType is KexInt && rhv.type is KexBool -> predicate(type, loc) { lhv.store(rhv `as` KexInt) }
            else -> predicate
        }
    }

    override fun transformEqualsTerm(term: EqualsTerm): Term {
        val lhv = term.lhv
        val rhv = term.rhv
        val bothOfThemAreInt = lhv.type is KexInteger && rhv.type is KexInteger
        val oneOfThemIsBool = lhv.type is KexBool || rhv.type is KexBool
        return when {
            lhv.type == rhv.type -> term
            oneOfThemIsBool && bothOfThemAreInt -> term { (lhv `as` KexBool) eq (rhv `as` KexBool) }
            else -> term
        }
    }

    private fun adaptTerm(term: Term) = when (term.type) {
        is KexBool -> term { term `as` KexInt }
        is KexInt -> term
        is KexInteger -> term { term `as` KexInt }
        else -> unreachable { log.error("Non-boolean term in boolean binary: $term") }
    }

    override fun transformBinaryTerm(term: BinaryTerm): Term {
        val isBooleanOpcode = when (term.opcode) {
            BinaryOpcode.AND -> true
            BinaryOpcode.OR -> true
            BinaryOpcode.XOR -> true
            else -> false
        }
        return when {
            term.lhv.type == term.rhv.type -> term
            isBooleanOpcode -> {
                val lhv = adaptTerm(term.lhv)
                val rhv = adaptTerm(term.rhv)
                val newType = mergeTypes(types, lhv.type, rhv.type)
                term { lhv.apply(newType, term.opcode, rhv) }
            }

            else -> term
        }
    }

    override fun transformCmpTerm(term: CmpTerm): Term = when {
        term.lhv.type == term.rhv.type -> term
        term.lhv.type is KexInteger || term.rhv.type is KexInteger -> {
            val lhv = adaptTerm(term.lhv)
            val rhv = adaptTerm(term.rhv)
            term { lhv.apply(term.opcode, rhv) }
        }

        else -> term
    }
}
