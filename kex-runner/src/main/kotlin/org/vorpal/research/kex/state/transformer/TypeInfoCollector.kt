package org.vorpal.research.kex.state.transformer

import org.vorpal.research.kex.ktype.KexInteger
import org.vorpal.research.kex.ktype.KexReal
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kex.smt.SMTModel
import org.vorpal.research.kex.state.BasicState
import org.vorpal.research.kex.state.ChainState
import org.vorpal.research.kex.state.ChoiceState
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.emptyState
import org.vorpal.research.kex.state.predicate.ArrayInitializerPredicate
import org.vorpal.research.kex.state.predicate.ArrayStorePredicate
import org.vorpal.research.kex.state.predicate.EqualityPredicate
import org.vorpal.research.kex.state.predicate.FieldInitializerPredicate
import org.vorpal.research.kex.state.predicate.FieldStorePredicate
import org.vorpal.research.kex.state.predicate.InequalityPredicate
import org.vorpal.research.kex.state.predicate.NewArrayInitializerPredicate
import org.vorpal.research.kex.state.predicate.NewArrayPredicate
import org.vorpal.research.kex.state.predicate.NewInitializerPredicate
import org.vorpal.research.kex.state.predicate.NewPredicate
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.predicate.path
import org.vorpal.research.kex.state.term.ArrayLoadTerm
import org.vorpal.research.kex.state.term.CastTerm
import org.vorpal.research.kex.state.term.FieldLoadTerm
import org.vorpal.research.kex.state.term.InstanceOfTerm
import org.vorpal.research.kex.state.term.NullTerm
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kfg.type.TypeFactory
import org.vorpal.research.kfg.type.stringType
import org.vorpal.research.kthelper.logging.log

@Suppress("unused")
enum class Nullability {
    UNKNOWN, NULLABLE, NON_NULLABLE
}

sealed class TypeInfo

data class NullabilityInfo(val nullability: Nullability) : TypeInfo()
data class CastTypeInfo(val type: KexType) : TypeInfo()

fun Map<Term, KexType>.toTypeMap() = TypeInfoMap.create(this)

class TypeInfoMap(val inner: Map<Term, Set<TypeInfo>> = hashMapOf()) : Map<Term, Set<TypeInfo>> by inner {
    inline fun <reified T : TypeInfo> getInfo(term: Term): T? = inner[term]?.mapNotNull { it as? T }?.also {
        assert(it.size <= 1) { log.warn("A lot of type information ${T::class.qualifiedName} about $term: $it") }
    }?.firstOrNull()

    companion object {
        fun create(tf: TypeFactory, map: Map<Term, Set<TypeInfo>>): TypeInfoMap {
            return TypeInfoMap(
                map.map { (term, types) ->
                    val reducedTypes = run {
                        val nullabilityInfo = types.filterIsInstance<NullabilityInfo>()
                        val castInfo = types.filterIsInstance<CastTypeInfo>()
                        val reducedCastInfo = mutableSetOf<CastTypeInfo>()
                        val castTypes = castInfo.map { it.type.getKfgType(tf) }
                        for (type in castTypes) {
                            if (castTypes.any { it != type && type.isSupertypeOf(it) }) continue
                            else reducedCastInfo += CastTypeInfo(type.kexType)
                        }
                        (nullabilityInfo + reducedCastInfo).toSet()
                    }
                    if (reducedTypes.isEmpty()) null
                    else term to reducedTypes
                }.filterNotNull().toMap()
            )
        }

        fun create(map: Map<Term, KexType>): TypeInfoMap {
            return TypeInfoMap(
                map.mapValues { (_, type) ->
                    setOf(CastTypeInfo(type))
                }
            )
        }
    }

    fun mapKeys(mapper: (Map.Entry<Term, Set<TypeInfo>>) -> Term) = TypeInfoMap(inner.mapKeys(mapper))
    fun mapValues(mapper: (Map.Entry<Term, Set<TypeInfo>>) -> Set<TypeInfo>) = TypeInfoMap(inner.mapValues(mapper))

    operator fun plus(other: TypeInfoMap) = TypeInfoMap(inner + other.inner)
    operator fun plus(other: Map<Term, KexType>) =
        TypeInfoMap(inner + other.mapValues { setOf(CastTypeInfo(it.value)) })

    fun toMap(): Map<Term, KexType> {
        val result = mutableMapOf<Term, KexType>()
        for (key in keys) {
            val typeInfo = getInfo<CastTypeInfo>(key) ?: continue
            result[key] = typeInfo.type
        }
        return result
    }
}

class TypeInfoCollector(
    val model: SMTModel,
    private val tf: TypeFactory
) : Transformer<TypeInfoCollector> {
    private val typeInfos = mutableMapOf<Term, MutableMap<TypeInfo, PredicateState>>()
    private val cfgTracker = CFGTracker()

    val infos: TypeInfoMap
        get() = TypeInfoMap.create(
            tf,
            typeInfos.map { (term, map) ->
                val types = map.filter { checkPath(model, it.value) }.keys
                term to types
            }.toMap()
        )

    private infix fun PredicateState.or(predicates: Set<Predicate>): PredicateState {
        return ChoiceState(listOf(this, BasicState(predicates.toList()))).simplify()
    }

    private infix fun PredicateState.or(other: PredicateState): PredicateState {
        return ChoiceState(listOf(this, other)).simplify()
    }

    private infix fun PredicateState.and(predicates: Set<Predicate>): PredicateState {
        return ChainState(this, BasicState(predicates.toList())).simplify()
    }

    private fun copyInfos(from: Term, to: Term, condition: Set<Predicate>) {
        val fromInfo = typeInfos.getOrElse(from, ::mutableMapOf)
        val toInfos = typeInfos.getOrPut(to, ::mutableMapOf)
        for ((info, conditions) in toInfos) {
            val moreCond = fromInfo.getOrDefault(info, emptyState())
            toInfos[info] = conditions or (moreCond and condition)
        }
        for ((info, conditions) in fromInfo.filter { it.key !in toInfos }) {
            toInfos[info] = conditions and condition
        }
    }

    override fun apply(ps: PredicateState): PredicateState {
        cfgTracker.apply(ps)
        return super.apply(ps)
    }

    override fun transformEquality(predicate: EqualityPredicate): Predicate {
        val condition = cfgTracker.getDominatingPaths(predicate)
        when (val rhv = predicate.rhv) {
            is InstanceOfTerm -> {
                val checkedType = CastTypeInfo(rhv.checkedType)
                val operand = rhv.operand
                val fullPath = condition + path { predicate.lhv equality true }

                val typeInfo = typeInfos.getOrPut(operand, ::mutableMapOf)
                val existingCond = typeInfo.getOrDefault(checkedType, emptyState())
                typeInfo[checkedType] = existingCond or fullPath
            }

            is CastTerm -> {
                val newType = CastTypeInfo(rhv.type)
                val operand = rhv.operand
                val stub = when {
                    condition.isEmpty() -> setOf(path { const(true) equality true })
                    else -> condition
                }

                // we can't do anything with primary type casts
                if (newType.type is KexInteger || newType.type is KexReal) return predicate

                val typeInfo = typeInfos.getOrPut(operand, ::mutableMapOf)
                val existingCond = typeInfo.getOrDefault(newType, emptyState())
                typeInfo[newType] = existingCond or stub
            }

            is FieldLoadTerm -> copyInfos(rhv.field, predicate.lhv, condition)
            is ArrayLoadTerm -> copyInfos(rhv.arrayRef, predicate.lhv, condition)
            else -> copyInfos(rhv, predicate.lhv, condition)
        }
        return super.transformEquality(predicate)
    }

    override fun transformInequality(predicate: InequalityPredicate): Predicate {
        when (predicate.rhv) {
            is NullTerm -> {
                val nullability = NullabilityInfo(Nullability.NON_NULLABLE)
                val condition = cfgTracker.getDominatingPaths(predicate)
                val lhv = predicate.lhv

                val typeInfo = typeInfos.getOrPut(lhv, ::mutableMapOf)
                val existingCond = typeInfo.getOrDefault(nullability, emptyState())
                typeInfo[nullability] = existingCond or condition
            }
        }
        return super.transformInequality(predicate)
    }

    override fun transformFieldInitializer(predicate: FieldInitializerPredicate): Predicate {
        copyInfos(predicate.value, predicate.field, cfgTracker.getDominatingPaths(predicate))
        return super.transformFieldInitializer(predicate)
    }

    override fun transformFieldStore(predicate: FieldStorePredicate): Predicate {
        copyInfos(predicate.value, predicate.field, cfgTracker.getDominatingPaths(predicate))
        return super.transformFieldStore(predicate)
    }

    override fun transformArrayInitializer(predicate: ArrayInitializerPredicate): Predicate {
        copyInfos(predicate.value, predicate.arrayRef, cfgTracker.getDominatingPaths(predicate))
        return super.transformArrayInitializer(predicate)
    }

    override fun transformArrayStore(predicate: ArrayStorePredicate): Predicate {
        copyInfos(predicate.value, predicate.arrayRef, cfgTracker.getDominatingPaths(predicate))
        return super.transformArrayStore(predicate)
    }
}

class PlainTypeInfoCollector(
    private val tf: TypeFactory
) : Transformer<TypeInfoCollector> {
    private val typeInfos = mutableMapOf<Term, MutableSet<TypeInfo>>()

    val infos: TypeInfoMap
        get() = TypeInfoMap.create(tf, typeInfos)

    override fun transformEquality(predicate: EqualityPredicate): Predicate {
        @Suppress("unused")
        when (val rhv = predicate.rhv) {
            is InstanceOfTerm -> {
                val checkedType = CastTypeInfo(rhv.checkedType)
                val operand = rhv.operand

                val typeInfo = typeInfos.getOrPut(operand, ::mutableSetOf)
                typeInfo += checkedType
            }

            is CastTerm -> {
                val newType = CastTypeInfo(rhv.type)
                val operand = rhv.operand

                // we can't do anything with primary type casts
                if (newType.type is KexInteger || newType.type is KexReal) return predicate

                val typeInfo = typeInfos.getOrPut(operand, ::mutableSetOf)
                typeInfo += newType
            }
        }
        return super.transformEquality(predicate)
    }

    override fun transformInequality(predicate: InequalityPredicate): Predicate {
        when (predicate.rhv) {
            is NullTerm -> {
                val nullability = NullabilityInfo(Nullability.NON_NULLABLE)
                val lhv = predicate.lhv

                val typeInfo = typeInfos.getOrPut(lhv, ::mutableSetOf)
                typeInfo += nullability
            }
        }
        return super.transformInequality(predicate)
    }
}

class StaticTypeInfoCollector(
    private val tf: TypeFactory,
    tip: TypeInfoMap = TypeInfoMap()
) : Transformer<StaticTypeInfoCollector> {
    val typeInfoMap = tip.toMap().toMutableMap()

    override fun transformTerm(term: Term): Term {
        if (term.type.getKfgType(tf) == tf.stringType && term !in typeInfoMap) {
            typeInfoMap[term] = term.type
        }
        return super.transformTerm(term)
    }

    override fun transformNewPredicate(predicate: NewPredicate): Predicate {
        if (predicate.lhv !in typeInfoMap) {
            typeInfoMap[predicate.lhv] = predicate.lhv.type
        }
        return super.transformNewPredicate(predicate)
    }

    override fun transformNewInitializerPredicate(predicate: NewInitializerPredicate): Predicate {
        if (predicate.lhv !in typeInfoMap) {
            typeInfoMap[predicate.lhv] = predicate.lhv.type
        }
        return super.transformNewInitializerPredicate(predicate)
    }

    override fun transformNewArrayPredicate(predicate: NewArrayPredicate): Predicate {
        if (predicate.lhv !in typeInfoMap) {
            typeInfoMap[predicate.lhv] = predicate.lhv.type
        }
        return super.transformNewArrayPredicate(predicate)
    }

    override fun transformNewArrayInitializerPredicate(predicate: NewArrayInitializerPredicate): Predicate {
        if (predicate.lhv !in typeInfoMap) {
            typeInfoMap[predicate.lhv] = predicate.lhv.type
        }
        return super.transformNewArrayInitializerPredicate(predicate)
    }
}

fun collectStaticTypeInfo(tf: TypeFactory, state: PredicateState, tip: TypeInfoMap = TypeInfoMap()): TypeInfoMap {
    val typeInfoCollector = StaticTypeInfoCollector(tf, tip).also { it.apply(state) }
    return TypeInfoMap.create(typeInfoCollector.typeInfoMap)
}

@Suppress("unused")
fun collectTypeInfos(model: SMTModel, tf: TypeFactory, ps: PredicateState): TypeInfoMap {
    val tic = TypeInfoCollector(model, tf)
    tic.apply(ps)
    return tic.infos
}

fun collectPlainTypeInfos(tf: TypeFactory, ps: PredicateState): TypeInfoMap {
    val tic = PlainTypeInfoCollector(tf)
    tic.apply(ps)
    return tic.infos
}
