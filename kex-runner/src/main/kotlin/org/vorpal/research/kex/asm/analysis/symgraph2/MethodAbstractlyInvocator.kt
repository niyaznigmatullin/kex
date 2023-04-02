package org.vorpal.research.kex.asm.analysis.symgraph2

import kotlinx.collections.immutable.*
import kotlinx.coroutines.yield
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symbolic.*
import org.vorpal.research.kex.descriptor.ConstantDescriptor
import org.vorpal.research.kex.descriptor.Descriptor
import org.vorpal.research.kex.descriptor.ObjectDescriptor
import org.vorpal.research.kex.ktype.KexClass
import org.vorpal.research.kex.ktype.KexNull
import org.vorpal.research.kex.ktype.KexRtManager.isJavaRt
import org.vorpal.research.kex.ktype.KexRtManager.rtMapped
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.smt.AsyncChecker
import org.vorpal.research.kex.smt.Result
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.predicate.NewPredicate
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.predicate.path
import org.vorpal.research.kex.state.predicate.state
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.transformer.*
import org.vorpal.research.kex.trace.symbolic.*
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.instruction.ReturnInst
import org.vorpal.research.kfg.type.NullType
import org.vorpal.research.kthelper.collection.queueOf

class MethodAbstractlyInvocator(
    ctx: ExecutionContext,
    rootMethod: Method,
) : SymbolicTraverser(ctx, rootMethod) {
    override val pathSelector: SymbolicPathSelector = DequePathSelector()
    override val callResolver: SymbolicCallResolver = DefaultCallResolver(ctx)
    override val invokeDynamicResolver: SymbolicInvokeDynamicResolver = DefaultCallResolver(ctx)
    private val activeObjectsBefore = mutableMapOf<Term, GraphObject>()
    private val allObjectsBefore = mutableMapOf<Term, GraphObject>()
    private val invocationPaths = mutableListOf<CallResult>()
    private val termsOfFieldsBefore = mutableMapOf<Pair<Term, String>, Term>()

    suspend fun invokeMethod(
        heapState: HeapState,
        thisArg: GraphObject,
        arguments: List<GraphObject>
    ): Collection<CallResult> {
        val firstInstruction = rootMethod.body.entry.instructions.first()
        val terms = mutableMapOf<GraphObject, Term>()
        val statePredicates = mutableListOf<Predicate>()
        val nullCheckPredicates = mutableListOf<Predicate>()
        if (thisArg == GraphObject.Null && !rootMethod.isStatic) {
            return emptyList()
        }
        val objects = heapState.objects
        objects.forEach {
            when (it) {
                GraphObject.Null -> {
                    terms[it] = const(null)
                }

                else -> {
                    val newTerm = generate(it.type)
                    if (thisArg != it && !arguments.contains(it)) {
                        statePredicates.add(state { newTerm.new() })
                    }
                    nullCheckPredicates.add(path { (newTerm eq null) equality false })
                    terms[it] = newTerm
                }
            }
        }
        addObjectsFields(objects, terms, statePredicates)
        allObjectsBefore.clear()
        allObjectsBefore.putAll(terms.filterValues { it != const(null) }
            .map { (obj, term) -> term to obj })
        activeObjectsBefore.clear()
        activeObjectsBefore.putAll(allObjectsBefore.filterValues { it in heapState.activeObjects })
        invocationPaths.clear()
//        println(clauses)
        val thisValue = values.getThis(rootMethod.klass)
        val initialArguments = buildMap {
            val values = this@MethodAbstractlyInvocator.values
            if (!rootMethod.isStatic) {
                val thisTerm = `this`(rootMethod.klass.symbolicClass)
                this[thisValue] = thisTerm
                statePredicates.add(state { terms.getValue(thisArg) equality thisTerm })
//                this[thisValue] = terms.getValue(thisArg)
            }
            for ((index, type) in rootMethod.argTypes.withIndex()) {
                val argTerm = arg(type.symbolicType, index)
                this[values.getArgument(index, rootMethod, type)] = argTerm
                if (type.symbolicType is KexClass) {
                    statePredicates.add(state {
                        argTerm equality terms.getValue(arguments[index])
                    })
                }
            }
        }
        val initialTypeInfo = terms.map { (obj, term) ->
            val kfgType = when (term.type) {
                is KexNull -> NullType
                else -> obj.type.getKfgType(types)
            }
            term to kfgType
        }.toMap().toPersistentMap()
        val initialNullCheckTerms = terms.filterValues { it.type !is KexNull }.values.toPersistentSet()
        val initialTypeCheckedTerms = initialTypeInfo
        pathSelector.add(
            TraverserState(
                symbolicState = persistentSymbolicState(
                    state = statePredicates.map { StateClause(firstInstruction, it) }.toPersistentClauseState(),
                    path = nullCheckPredicates.map {
                        PathClause(PathClauseType.NULL_CHECK, firstInstruction, it)
                    }.toPersistentPathCondition(),
                ),
                valueMap = initialArguments.toPersistentMap(),
                stackTrace = persistentListOf(),
                typeInfo = initialTypeInfo,
                blockPath = persistentListOf(),
                nullCheckedTerms = initialNullCheckTerms,
                boundCheckedTerms = persistentSetOf(),
                typeCheckedTerms = initialTypeCheckedTerms
            ), rootMethod.body.entry
        )

        while (pathSelector.hasNext()) {
            val (currentState, currentBlock) = pathSelector.next()
            this@MethodAbstractlyInvocator.currentState = currentState
            traverseBlock(currentBlock)
            yield()
        }

        return invocationPaths
    }

    private fun addObjectsFields(
        objects: Collection<GraphObject>,
        terms: Map<GraphObject, Term>,
        statePredicates: MutableList<Predicate>
    ) {
        objects.filter { it != GraphObject.Null }.forEach { obj ->
            val objectTerm = terms.getValue(obj)
            addFields(
                obj.objectFields.mapValues { (_, obj) -> terms.getValue(obj) },
                objectTerm,
                statePredicates
            )
            addFields(obj.primitiveFields, objectTerm, statePredicates)
        }
    }

    private fun addFields(
        fieldsToAdd: Map<Pair<String, KexType>, Term>,
        objectTerm: Term,
        predicates: MutableList<Predicate>
    ) {
        for ((field, valueTerm) in fieldsToAdd) {
            val (fieldName, fieldType) = field
            predicates.add(state {
                objectTerm.field(fieldType, fieldName).store(valueTerm)
            })
            termsOfFieldsBefore[objectTerm to fieldName] = valueTerm
        }
    }

    private suspend fun checkAsyncAndGetReturn(
        method: Method,
        state: SymbolicState,
    ): Pair<Map<Term, Descriptor>, PredicateState> { // TODO make as checkAsync
        val checker = AsyncChecker(method, ctx)
        val clauses = state.clauses.asState()
        val query = state.path.asState()
        val concreteTypeInfo = state.concreteValueMap.mapValues { it.value.type }.filterValues { it.isJavaRt }
            .mapValues { it.value.rtMapped }.toTypeMap()
        val (predicateState, result) = checker.prepareAndCheckWithState(method, clauses + query, concreteTypeInfo)
        check(result is Result.SatResult)
        val descriptors = generateFinalObjectsState(method, ctx, result.model, checker.state)
        return descriptors to predicateState
    }

    override suspend fun traverseReturnInst(inst: ReturnInst) {
        val traverserState = currentState ?: return
        val stackTrace = traverserState.stackTrace
        val stackTraceElement = stackTrace.lastOrNull()
        val receiver = stackTraceElement?.instruction
        if (receiver == null) {
//            println("Return Instruction for method: $rootMethod")
            val result = check(rootMethod, traverserState.symbolicState)
            if (result != null) {
                val returnTerm = when {
                    inst.hasReturnValue -> traverserState.mkTerm(inst.returnValue)
                    else -> null
                }
//                println("objectsToLookAfter = $objectsToLookAfter")
                val (objectDescriptors, fullPredicateState) = checkAsyncAndGetReturn(
                    rootMethod,
                    traverserState.symbolicState,
                )
                report_(fullPredicateState, objectDescriptors, returnTerm?.let { objectDescriptors[it] })
            }
            currentState = null
        } else {
            super.traverseReturnInst(inst)
        }
    }

    private fun report_(
        predicateState: PredicateState,
        objectDescriptors: Map<Term, Descriptor>,
        returnDescriptor: Descriptor?
    ) {
//        println("{inst} = $inst {parameters} = $parameters {objectDescriptors} = $objectDescriptors")
        val activeDescriptors = getActiveDescriptors(objectDescriptors, returnDescriptor)
        val allDescriptors = findAllReachableDescriptors(activeDescriptors)
        val mapping = allDescriptors.associateWith {
            when (it) {
                ConstantDescriptor.Null -> GraphObject.Null
                else -> GraphObject(it.type)
            }
        }
        val newObjects = collectNewObjectTerms(predicateState)
        val representerObjects = buildSet {
            addAll(allObjectsBefore.keys)
            addAll(newObjects)
        }
        val representersByDescriptor = representerObjects.associateBy {
            objectDescriptors.getValue(it)
        }
        val mapToRepresenter = objectDescriptors.filterValues { it.type !is KexNull }.mapValues { (_, descriptor) ->
            representersByDescriptor.getValue(descriptor)
        }
//        println("before : $predicateState")
        val (fields, updatedState) = extractValues(termsOfFieldsBefore, mapToRepresenter, predicateState)
//        println("after : $updatedState")
        mapping.filter { it.key != ConstantDescriptor.Null }.forEach { (descriptor, obj) ->
            descriptor as ObjectDescriptor
            val representer = representersByDescriptor.getValue(descriptor)
            obj.objectFields = buildMap {
                for ((field, descriptorTo) in descriptor.fields) {
                    val fieldType = field.second
                    if (fieldType is KexClass) {
                        put(field, mapping.getValue(descriptorTo))
                    }
                }
            }
            obj.primitiveFields = buildMap {
                for ((field, _) in descriptor.fields) {
                    val (fieldName, fieldType) = field
                    if (fieldType !is KexClass) {
                        put(field, fields.getValue(representer to fieldName))
                    }
                }
            }
        }
        val activeObjects = buildSet {
            addAll(activeDescriptors.map { mapping.getValue(it) })
        }
        invocationPaths.add(
            CallResult(
                mapping.values,
                activeObjects,
                returnDescriptor?.let { mapping.getValue(it) },
                updatedState,
            )
        )
    }
//
//    private fun removeNonInterestingPredicates(ps: PredicateState, interestingTerms: Set<Term>): PredicateState {
//        val transformer = LeaveOnlyInterestingPredicates(interestingTerms)
//        return transformer.apply(ps)
//    }
//
//    class LeaveOnlyInterestingPredicates(val interestingTerms: Set<Term>): Transformer<LeaveOnlyInterestingPredicates> {
//        override fun transformBase(predicate: Predicate): Predicate {
//            if (TermCollector.getFullTermSet(predicate).any { interestingTerms.contains(it) }) {
//                return predicate
//            }
//            return nothing()
//        }
//    }

    private fun collectNewObjectTerms(predicateState: PredicateState): Set<Term> {
        val collector = PredicateTermCollector { it is NewPredicate }
        collector.apply(predicateState)
        return collector.terms
    }

    private fun getActiveDescriptors(
        objectDescriptors: Map<Term, Descriptor>,
        returnDescriptor: Descriptor?
    ) = buildSet {
        addAll(objectDescriptors.filterKeys { activeObjectsBefore.containsKey(it) }.values)
        returnDescriptor?.let<Descriptor, Unit> { add(it) }
    }

    private fun findAllReachableDescriptors(activeDescriptors: Set<Descriptor>): Set<Descriptor> {
        val allDescriptors = mutableSetOf<Descriptor>().also {
            it.addAll(activeDescriptors)
        }
        val queue = queueOf(activeDescriptors)
        while (queue.isNotEmpty()) {
            val obj = queue.poll()!!
            if (obj == ConstantDescriptor.Null) {
                continue
            }
            for ((field, descriptor) in (obj as ObjectDescriptor).fields) {
                if (field.second !is KexClass) {
                    continue
                }
                if (!allDescriptors.contains(descriptor)) {
                    allDescriptors.add(descriptor)
                    queue.add(descriptor)
                }
            }
        }
        return allDescriptors
    }
}
