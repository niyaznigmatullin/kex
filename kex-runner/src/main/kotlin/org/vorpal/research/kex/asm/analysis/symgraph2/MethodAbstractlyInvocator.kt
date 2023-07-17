package org.vorpal.research.kex.asm.analysis.symgraph2

import kotlinx.collections.immutable.*
import kotlinx.coroutines.yield
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.asm.analysis.symbolic.*
import org.vorpal.research.kex.asm.analysis.symgraph2.heapstate.HeapState
import org.vorpal.research.kex.asm.analysis.symgraph2.objects.*
import org.vorpal.research.kex.descriptor.*
import org.vorpal.research.kex.ktype.*
import org.vorpal.research.kex.ktype.KexRtManager.isJavaRt
import org.vorpal.research.kex.ktype.KexRtManager.rtMapped
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.smt.AsyncChecker
import org.vorpal.research.kex.smt.Result
import org.vorpal.research.kex.state.PredicateState
import org.vorpal.research.kex.state.fields.FieldContainer
import org.vorpal.research.kex.state.fields.MutableFieldContainer
import org.vorpal.research.kex.state.predicate.*
import org.vorpal.research.kex.state.term.ArgumentTerm
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.transformer.*
import org.vorpal.research.kex.trace.symbolic.*
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.Constant
import org.vorpal.research.kfg.ir.value.Value
import org.vorpal.research.kfg.ir.value.ValueFactory
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.ir.value.instruction.ReturnInst
import org.vorpal.research.kfg.type.NullType
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.collection.queueOf

class MethodAbstractlyInvocator(
    ctx: ExecutionContext,
    rootMethod: Method,
) : SymbolicTraverser(ctx, rootMethod) {
    override val pathSelector: SymbolicPathSelector = DequePathSelector()
    override val callResolver: SymbolicCallResolver = DefaultCallResolver(ctx)
    override val invokeDynamicResolver: SymbolicInvokeDynamicResolver = DefaultCallResolver(ctx)
    private val activeObjectsBefore = mutableMapOf<Term, GraphVertex>()
    private val allObjectsBefore = mutableMapOf<Term, GraphVertex>()
    private val invocationPaths = mutableListOf<CallResult>()
    private val termsOfFieldsBefore = MutableFieldContainer()

    fun getGeneratedInvocationPaths() = invocationPaths

    suspend fun invokeMethod(
        heapState: HeapState,
        thisArg: GraphVertex,
        arguments: List<Argument>
    ) {
        if (thisArg == GraphVertex.Null && (!rootMethod.isStatic && !rootMethod.isConstructor)) {
            return
        }
        val initializer = ObjectInitializer(heapState.objects, thisArg, arguments, rootMethod.isConstructor)
        val terms = initializer.generateObjectTerms()
        val statePredicates = initializer.statePredicates
        val nullCheckPredicates = initializer.nullCheckPredicates
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
                if (!rootMethod.isConstructor) {
                    statePredicates.add(state { terms.getValue(thisArg) equality thisTerm })
                }
            }
            for ((index, type) in rootMethod.argTypes.withIndex()) {
                val argTerm = arg(type.symbolicType, index)
                this[values.getArgument(index, rootMethod, type)] = argTerm
                if (type.symbolicType is KexClass) {
                    statePredicates.add(state {
                        argTerm equality terms.getValue((arguments[index] as ObjectArgument).obj)
                    })
                }
            }
        }
        val thisAndArgs = buildSet {
            add(terms.getValue(thisArg))
            addAll(arguments.filterIsInstance<ObjectArgument>().map { terms.getValue(it.obj) })
        }
        for (x in thisAndArgs) {
            for (y in thisAndArgs) {
                if (x == y) break
                statePredicates.add(state {
                    x inequality y
                })
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
        val firstInstruction = rootMethod.body.entry.instructions.first()
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
    }

    inner class ObjectInitializer(
        private val objects: Collection<GraphVertex>,
        private val thisArg: GraphVertex,
        private val arguments: List<Argument>,
        private val isConstructor: Boolean,
    ) {
        val statePredicates = mutableListOf<Predicate>()
        val nullCheckPredicates = mutableListOf<Predicate>()

        fun generateObjectTerms(): Map<GraphVertex, Term> {
            val terms = objects.associateWith {
                when (it) {
                    GraphVertex.Null -> const(null)
                    else -> generate(it.type)
                }
            }
            for ((obj, objectTerm) in terms.filter { it.key != GraphVertex.Null }) {
                obj as GraphObject
                if (thisArg != obj && !arguments.any { it is ObjectArgument && it.obj == obj }) {
                    statePredicates.add(state { objectTerm.new() })
                    addDefaultFields(objectTerm)
                }
                nullCheckPredicates.add(path { (objectTerm eq null) equality false })
                addFieldsTo(obj.objectFields.mapValues { terms.getValue(it.value) }, objectTerm)
                addFieldsTo(obj.primitiveFields, objectTerm)
            }
            if (isConstructor) {
                addDefaultFields(`this`(rootMethod.klass.symbolicClass))
            }
            return terms
        }

        private fun addDefaultFields(objectTerm: Term) {
            val fields = (objectTerm.type as KexClass).kfgClass(cm.type).fields
            for (f in fields) {
                if (f.isStatic) {
                    continue
                }
                val default = f.defaultValue
                val value = if (default == null) {
                    cm.value.getZero(f.type) as? Constant ?: unreachable("getZero returned not constant")
                } else {
                    default as? Constant ?: unreachable("default value is not constant")
                }
                val term = const(value)
                statePredicates.add(state {
                    objectTerm.field(f.type.symbolicType, f.name).store(term)
                })
            }
        }

        private fun addFieldsTo(fieldsToAdd: Map<Pair<String, KexType>, Term>, objectTerm: Term) {
            for ((field, valueTerm) in fieldsToAdd) {
                val (fieldName, fieldType) = field
                statePredicates.add(state {
                    objectTerm.field(fieldType, fieldName).store(valueTerm)
                })
                termsOfFieldsBefore.setField(objectTerm to fieldName, valueTerm)
            }
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
        check(result is Result.SatResult) {
            "result = ${result.javaClass}"
        }
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
                    rootMethod.isConstructor -> traverserState.mkTerm(values.getThis(rootMethod.klass))
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

    override fun report(inst: Instruction, parameters: Parameters<Descriptor>, testPostfix: String): Boolean {
        return false
    }

    private fun report_(
        predicateState: PredicateState,
        objectDescriptors: Map<Term, Descriptor>,
        returnDescriptor: Descriptor?
    ) {
        val activeDescriptors = getActiveDescriptors(objectDescriptors, returnDescriptor)
        val allDescriptors = findAllReachableDescriptors(activeDescriptors) ?: return
        val newObjects = collectNewObjectTerms(predicateState)
        val representerObjects = buildSet {
            addAll(allObjectsBefore.keys)
            addAll(newObjects)
        }
        val representersByDescriptor = representerObjects.associateBy {
            objectDescriptors.getValue(it)
        }
        val mapToRepresenter = objectDescriptors.filterValues { it is ObjectDescriptor }
            .mapValues { (_, descriptor) ->
                representersByDescriptor.getValue(descriptor)
            }
        var updatedState = BoolTypeAdapter(ctx.types).transform(predicateState)
        val fields = extractValues(termsOfFieldsBefore, mapToRepresenter, updatedState).let { (fields, state) ->
            updatedState = state
            fields
        }
        updatedState = removeNonInterestingPredicates(updatedState)
        val mapping = allDescriptors.associateWith {
            when (it) {
                ConstantDescriptor.Null -> GraphVertex.Null
                is ObjectDescriptor -> GraphObject(it.type as KexClass)
                else -> unreachable("only objects are supported")
            }
        }
        mapping.forEach { (descriptor, obj) ->
            when (obj) {
                is GraphObject -> {
                    val representer = representersByDescriptor.getValue(descriptor)
                    fillObjectFields(obj, descriptor as ObjectDescriptor, mapping, fields, representer)
                }

                else -> {}
            }
        }
        val activeObjects = buildSet {
            addAll(activeDescriptors.map { mapping.getValue(it) })
        }
        val activeObjectsMapping = buildMap {
            for ((term, oldObject) in activeObjectsBefore) {
                val descriptor = objectDescriptors.getValue(term)
                val newObject = mapping.getValue(descriptor)
                put(oldObject, newObject)
            }
            put(GraphVertex.Null, GraphVertex.Null)
        }
        invocationPaths.add(
            CallResult(
                mapping.values,
                activeObjects,
                activeObjectsMapping,
                returnDescriptor?.let { mapping.getValue(it) },
                updatedState,
            )
        )
    }

    private fun fillObjectFields(
        obj: GraphObject,
        descriptor: ObjectDescriptor,
        mapping: Map<Descriptor, GraphVertex>,
        fields: FieldContainer,
        representer: Term
    ) {
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
                    put(field, fields.getField(representer to fieldName))
                }
            }
        }
    }

    private fun convertArgsToFreeTerms(state: PredicateState): Pair<Collection<Term>, PredicateState> {
        val arguments = rootMethod.argTypes.withIndex()
            .filter { it.value.kexType !is KexClass }
            .associate { it.index to generate(it.value.kexType) }
        val replacer = ArgumentReplacer(arguments)
        val newState = replacer.apply(state)
        return arguments.values to newState
    }

    class ArgumentReplacer(private val mapping: Map<Int, Term>) : Transformer<ArgumentReplacer> {
        override fun transformArgument(term: ArgumentTerm): Term {
            return mapping[term.index] ?: term
        }
    }


    private fun removeNonInterestingPredicates(ps: PredicateState): PredicateState {
        val transformer = RemovePredicates()
        return transformer.apply(ps)
    }

    class RemovePredicates : Transformer<RemovePredicates> {
        override fun transformBase(predicate: Predicate): Predicate {
            if (TermCollector.getFullTermSet(predicate).any { it.type is KexClass }) {
                return nothing()
            }
            return super.transformBase(predicate)
        }

        override fun transformNew(predicate: NewPredicate): Predicate {
            return nothing()
        }
    }

    private fun collectNewObjectTerms(predicateState: PredicateState): Set<Term> {
        val collector = PredicateTermCollector { it is NewPredicate || it is NewInitializerPredicate }
        collector.apply(predicateState)
        return if (!rootMethod.isConstructor) {
            collector.terms
        } else {
            val thisCollector = TermCollector { it.name == "this" }
            thisCollector.apply(predicateState)
            buildSet {
                addAll(collector.terms)
                addAll(thisCollector.terms)
            }
        }
    }

    private fun getActiveDescriptors(
        objectDescriptors: Map<Term, Descriptor>,
        returnDescriptor: Descriptor?
    ) = buildSet {
        addAll(objectDescriptors.filterKeys { activeObjectsBefore.containsKey(it) }.values)
        add(ConstantDescriptor.Null)
        returnDescriptor?.let<Descriptor, Unit> { add(it) }
    }

    private fun findAllReachableDescriptors(from: Set<Descriptor>): Set<Descriptor>? {
        val allDescriptors = from.toMutableSet()
        val queue = queueOf(from)
        while (queue.isNotEmpty()) {
            val obj = queue.poll()!!
            val pointsTo = when (obj) {
                is ObjectDescriptor -> {
                    obj.fields.map { (_, descriptor) -> descriptor }
                }

                is ArrayDescriptor -> {
//                    obj.elements.map { (_, descriptor) -> descriptor }
                    return null
                }

                else -> emptyList()
            }.filter { it.type is KexPointer }
            for (descriptor in pointsTo) {
                if (!allDescriptors.contains(descriptor)) {
                    allDescriptors.add(descriptor)
                    queue.add(descriptor)
                }
            }
        }
        return allDescriptors
    }
}
