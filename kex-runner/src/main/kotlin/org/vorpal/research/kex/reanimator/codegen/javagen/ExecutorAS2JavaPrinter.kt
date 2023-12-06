package org.vorpal.research.kex.reanimator.codegen.javagen

import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.config.kexConfig
import org.vorpal.research.kex.ktype.KexBool
import org.vorpal.research.kex.ktype.KexByte
import org.vorpal.research.kex.ktype.KexChar
import org.vorpal.research.kex.ktype.KexDouble
import org.vorpal.research.kex.ktype.KexFloat
import org.vorpal.research.kex.ktype.KexInt
import org.vorpal.research.kex.ktype.KexLong
import org.vorpal.research.kex.ktype.KexShort
import org.vorpal.research.kex.ktype.KexType
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.reanimator.actionsequence.ActionList
import org.vorpal.research.kex.reanimator.actionsequence.ActionSequence
import org.vorpal.research.kex.reanimator.actionsequence.ConstructorCall
import org.vorpal.research.kex.reanimator.actionsequence.DefaultConstructorCall
import org.vorpal.research.kex.reanimator.actionsequence.EnumValueCreation
import org.vorpal.research.kex.reanimator.actionsequence.ExternalConstructorCall
import org.vorpal.research.kex.reanimator.actionsequence.ExternalMethodCall
import org.vorpal.research.kex.reanimator.actionsequence.InnerClassConstructorCall
import org.vorpal.research.kex.reanimator.actionsequence.MethodCall
import org.vorpal.research.kex.reanimator.actionsequence.NewArray
import org.vorpal.research.kex.reanimator.actionsequence.PrimaryValue
import org.vorpal.research.kex.reanimator.actionsequence.ReflectionArrayWrite
import org.vorpal.research.kex.reanimator.actionsequence.ReflectionList
import org.vorpal.research.kex.reanimator.actionsequence.ReflectionNewArray
import org.vorpal.research.kex.reanimator.actionsequence.ReflectionNewInstance
import org.vorpal.research.kex.reanimator.actionsequence.ReflectionSetField
import org.vorpal.research.kex.reanimator.actionsequence.ReflectionSetStaticField
import org.vorpal.research.kex.reanimator.actionsequence.StaticFieldGetter
import org.vorpal.research.kex.reanimator.actionsequence.StringValue
import org.vorpal.research.kex.reanimator.actionsequence.UnknownSequence
import org.vorpal.research.kfg.type.ArrayType
import org.vorpal.research.kfg.type.ClassType
import org.vorpal.research.kfg.type.PrimitiveType
import org.vorpal.research.kfg.type.Type
import org.vorpal.research.kfg.type.objectType
import org.vorpal.research.kthelper.assert.unreachable
import org.vorpal.research.kthelper.logging.log
import org.vorpal.research.kthelper.runIf
import kotlin.time.Duration.Companion.seconds


class ExecutorAS2JavaPrinter(
    ctx: ExecutionContext,
    packageName: String,
    klassName: String,
    private val setupName: String
) : ActionSequence2JavaPrinter(ctx, packageName, klassName) {
    private val surroundInTryCatch = kexConfig.getBooleanValue("testGen", "surroundInTryCatch", true)
    private val testTimeout = kexConfig.getIntValue("testGen", "testTimeout", 10).seconds
    private val testParams = mutableListOf<JavaBuilder.JavaClass.JavaField>()
    private val reflectionUtils = ReflectionUtilsPrinter.reflectionUtils(packageName)
    private val printedDeclarations = hashSetOf<String>()
    private val printedInsides = hashSetOf<String>()

    private val KexType.primitiveName: String?
        get() = when (this) {
            is KexBool -> "boolean"
            is KexByte -> "byte"
            is KexChar -> "char"
            is KexShort -> "short"
            is KexInt -> "int"
            is KexLong -> "long"
            is KexFloat -> "float"
            is KexDouble -> "double"
            else -> null
        }

    override fun cleanup() {
        super.cleanup()
        testParams.clear()
        printedDeclarations.clear()
        printedInsides.clear()
    }

    override fun printActionSequence(
        testName: String,
        method: org.vorpal.research.kfg.ir.Method,
        actionSequences: Parameters<ActionSequence>
    ) {
        cleanup()

        for (cs in actionSequences.asList)
            resolveTypes(cs)

        with(builder) {
            import("org.junit.Before")
            import("java.lang.Class")
            import("java.lang.reflect.Method")
            import("java.lang.reflect.Constructor")
            import("java.lang.reflect.Field")
            import("java.lang.reflect.Array")
            importStatic("${reflectionUtils.klass.pkg}.${reflectionUtils.klass.name}.*")

            with(klass) {
                if (generateSetup) {
                    runIf(!method.isConstructor) {
                        actionSequences.instance?.let {
                            if (!it.isConstantValue)
                                testParams += field(it.name, type("Object"))
                        }
                    }
                    actionSequences.arguments.forEach { arg ->
                        val type = when (arg) {
                            is UnknownSequence -> arg.type
                            is ActionList -> arg.firstNotNullOfOrNull {
                                when (it) {
                                    is DefaultConstructorCall -> it.klass.asType
                                    is ConstructorCall -> it.constructor.klass.asType
                                    is NewArray -> it.asArray
                                    is ExternalConstructorCall -> it.constructor.returnType
                                    is ExternalMethodCall -> it.method.returnType
                                    is InnerClassConstructorCall -> it.constructor.klass.asType
                                    is EnumValueCreation -> it.klass.asType
                                    is StaticFieldGetter -> it.field.type
                                    else -> null
                                }
                            } ?: unreachable { log.error("Unexpected call in arg") }

                            is ReflectionList -> arg.firstNotNullOfOrNull {
                                when (it) {
                                    is ReflectionNewInstance -> it.type
                                    is ReflectionNewArray -> it.type
                                    else -> null
                                }
                            } ?: unreachable { log.error("Unexpected call in arg") }

                            is PrimaryValue<*> -> return@forEach
                            is StringValue -> return@forEach
                            else -> unreachable { log.error("Unexpected call in arg") }
                        }
                        val fieldType = type.kexType.primitiveName?.let { type(it) } ?: type("Object")
                        if (testParams.all { it.name != arg.name }) {
                            testParams += field(arg.name, fieldType)
                        }
                    }
                }

                current = if (generateSetup) method(setupName) {
                    returnType = void
                    annotations += "Before"
                    exceptions += "Throwable"
                } else method(testName) {
                    returnType = void
                    annotations += "Test(timeout = ${testTimeout.inWholeMilliseconds})"
                    exceptions += "Throwable"
                }
            }
        }

        with(current) {
            if (surroundInTryCatch) statement("try {")
            runIf(!method.isConstructor) {
                actionSequences.instance?.printAsJava()
            }
            for (cs in actionSequences.asList)
                cs.printAsJava()
            if (surroundInTryCatch) statement("} catch (Throwable e) {}")
        }

        printedStacks.clear()
        if (generateSetup) {
            with(builder) {
                with(klass) {
                    current = method(testName) {
                        returnType = void
                        annotations += "Test"
                        exceptions += "Throwable"
                    }
                }
            }
        }

        with(current) {
            if (surroundInTryCatch) statement("try {")
            printTestCall(method, actionSequences)
            if (surroundInTryCatch) statement("} catch (Throwable e) {}")
        }
    }

    override fun printVarDeclaration(name: String, type: ASType) = when {
        testParams.any { it.name == name } -> name
        else -> super.printVarDeclaration(name, type)
    }

    @Suppress("RecursivePropertyAccessor")
    private val Type.klassType: String
        get() = when (this) {
            is PrimitiveType -> "${kexType.primitiveName}.class"
            is ClassType -> "Class.forName(\"${klass.canonicalDesc}\")"
            is ArrayType -> "Array.newInstance(${component.klassType}, 0).getClass()"
            else -> unreachable { }
        }

    private fun printTestCall(
        method: org.vorpal.research.kfg.ir.Method,
        actionSequences: Parameters<ActionSequence>
    ) =
        with(current) {
            +"Class<?> klass = Class.forName(\"${method.klass.canonicalDesc}\")"
            +"Class<?>[] argTypes = new Class<?>[${method.argTypes.size}]"
            for ((index, type) in method.argTypes.withIndex()) {
                +"argTypes[$index] = ${type.klassType}"
            }
            +"Object[] args = new Object[${method.argTypes.size}]"
            for ((index, arg) in actionSequences.arguments.withIndex()) {
                +"args[$index] = ${arg.stackName}"
            }
            +when {
                method.isConstructor -> "${reflectionUtils.callConstructor.name}(klass, argTypes, args)"
                else -> "${reflectionUtils.callMethod.name}(klass, \"${method.name}\", argTypes, ${actionSequences.instance?.name}, args)"
            }
        }

    override fun printConstructorCall(owner: ActionSequence, call: ConstructorCall): List<String> {
        call.args.forEach { it.printAsJava() }
        val args = call.args.joinToString(", ") {
            val prefix = if (!it.isConstantValue) "(${resolvedTypes[it]}) " else ""
            prefix + it.stackName
        }
        val actualType = ASClass(call.constructor.klass.asType)
        return listOf(
            if (resolvedTypes[owner] != null) {
                val rest = resolvedTypes[owner]!!
                val type = actualType.merge(rest)
                actualTypes[owner] = type
                "${printVarDeclaration(owner.name, type)} = new $type($args)"
            } else {
                actualTypes[owner] = actualType
                "${printVarDeclaration(owner.name, actualType)} = new $actualType($args)"
            }
        )
    }

    override fun printMethodCall(owner: ActionSequence, call: MethodCall): List<String> {
        call.args.forEach { it.printAsJava() }
        val method = call.method
        val args = call.args.joinToString(", ") {
            it.forceCastIfNull(resolvedTypes[it])
        }
        return listOf("((${actualTypes[owner]}) ${owner.name}).${method.name}($args)")
    }

    override fun printExternalConstructorCall(
        owner: ActionSequence,
        call: ExternalConstructorCall
    ): List<String> = printConstructorCommon(owner, call) { args ->
        val constructor = call.constructor
        args.withIndex().joinToString(", ") { (index, arg) ->
            "(${constructor.argTypes[index].javaString}) ${arg.stackName}"
        }
    }

    override fun printExternalMethodCall(
        owner: ActionSequence,
        call: ExternalMethodCall
    ): List<String> = printExternalMethodCallCommon(
        owner,
        call,
        { instance -> "((${call.method.klass.javaString}) ${instance.stackName})" },
        { args ->
            args.withIndex().joinToString(", ") { (index, arg) ->
                "(${call.method.argTypes[index].javaString}) ${arg.stackName}"
            }
        }
    )

    override fun printReflectionList(reflectionList: ReflectionList): List<String> {
        val res = mutableListOf<String>()
        printDeclarations(reflectionList, res)
        printInsides(reflectionList, res)
        return res
    }

    private fun printDeclarations(
        owner: ActionSequence,
        result: MutableList<String>
    ) {
        if (owner is ReflectionList) {
            if (owner.name in printedDeclarations) return
            printedDeclarations += owner.name

            for (api in owner) {
                when (api) {
                    is ReflectionNewInstance -> result += printReflectionNewInstance(owner, api)
                    is ReflectionNewArray -> result += printReflectionNewArray(owner, api)
                    is ReflectionSetField -> printDeclarations(api.value, result)
                    is ReflectionSetStaticField -> printDeclarations(api.value, result)
                    is ReflectionArrayWrite -> printDeclarations(api.value, result)
                }
            }
        } else {
            owner.printAsJava()
        }
    }

    private fun printInsides(
        owner: ActionSequence,
        result: MutableList<String>
    ) {
        if (owner is ReflectionList) {
            if (owner.name in printedInsides) return
            printedInsides += owner.name

            for (api in owner) {
                when (api) {
                    is ReflectionSetField -> {
                        printInsides(api.value, result)
                        result += printReflectionSetField(owner, api)
                    }

                    is ReflectionSetStaticField -> {
                        printInsides(api.value, result)
                        result += printReflectionSetStaticField(owner, api)
                    }

                    is ReflectionArrayWrite -> {
                        printInsides(api.value, result)
                        result += printReflectionArrayWrite(owner, api)
                    }

                    else -> {}
                }
            }
        } else {
            owner.printAsJava()
        }
    }

    override fun printReflectionNewInstance(owner: ActionSequence, call: ReflectionNewInstance): List<String> {
        val actualType = ASClass(ctx.types.objectType)
        val kfgClass = (call.type as ClassType).klass
        return listOf(
            if (resolvedTypes[owner] != null) {
                val rest = resolvedTypes[owner]!!
                val type = actualType.merge(rest)
                actualTypes[owner] = type
                "${
                    printVarDeclaration(
                        owner.name,
                        type
                    )
                } = ${reflectionUtils.newInstance.name}(Class.forName(\"${kfgClass.canonicalDesc}\"))"
            } else {
                actualTypes[owner] = actualType
                "${
                    printVarDeclaration(
                        owner.name,
                        actualType
                    )
                } = ${reflectionUtils.newInstance.name}(Class.forName(\"${kfgClass.canonicalDesc}\"))"
            }
        )
    }

    private fun getClass(type: Type): String = when {
        type.isPrimitive -> "${type.kexType.primitiveName}.class"
        type is ClassType -> "Class.forName(\"${type.klass.canonicalDesc}\")"
        type is ArrayType -> "Array.newInstance(${getClass(type.component)}, 0).getClass()"
        else -> "Class.forName(\"java.lang.Object\")"
    }

    override fun printReflectionNewArray(owner: ActionSequence, call: ReflectionNewArray): List<String> {
        val elementType = call.asArray.component
        val (newArrayCall, actualType) = when {
            elementType.isPrimitive -> {
                "${reflectionUtils.newPrimitiveArrayMap[elementType.kexType.primitiveName]!!.name}(${call.length.stackName})" to call.asArray.asType
            }

            elementType is ClassType -> {
                "${reflectionUtils.newArray.name}(\"${elementType.klass.canonicalDesc}\", ${call.length.stackName})" to ASArray(
                    ASClass(
                        ctx.types.objectType
                    )
                )
            }

            else -> {
                val base = run {
                    var current = elementType
                    while (current is ArrayType) {
                        current = current.component
                    }
                    current
                }
                when {
                    base.isPrimitive -> {
                        "${reflectionUtils.newArray.name}(\"${elementType.canonicalDesc}\", ${call.length.stackName})" to ASArray(
                            ASClass(ctx.types.objectType)
                        )
                    }

                    else -> {
                        "${reflectionUtils.newObjectArray.name}(${getClass(elementType)}, ${call.length.stackName})" to ASArray(
                            ASClass(
                                ctx.types.objectType
                            )
                        )
                    }
                }
            }
        }
        actualTypes[owner] = actualType
        return listOf(
            "${printVarDeclaration(owner.name, actualType)} = ($actualType) $newArrayCall"
        )
    }

    override fun printReflectionSetField(owner: ActionSequence, call: ReflectionSetField): List<String> {
        val setFieldMethod = call.field.type.kexType.primitiveName?.let { reflectionUtils.setPrimitiveFieldMap[it]!! }
            ?: reflectionUtils.setField
        return listOf("${setFieldMethod.name}(${owner.name}, ${owner.name}.getClass(), \"${call.field.name}\", ${call.value.stackName})")
    }

    override fun printReflectionSetStaticField(owner: ActionSequence, call: ReflectionSetStaticField): List<String> {
        val setFieldMethod = call.field.type.kexType.primitiveName?.let { reflectionUtils.setPrimitiveFieldMap[it]!! }
            ?: reflectionUtils.setField
        return listOf("${setFieldMethod.name}(null, Class.forName(\"${call.field.klass.canonicalDesc}\"), \"${call.field.name}\", ${call.value.stackName})")
    }

    override fun printReflectionArrayWrite(owner: ActionSequence, call: ReflectionArrayWrite): List<String> {
        val elementType = call.elementType
        val setElementMethod = elementType.kexType.primitiveName?.let { reflectionUtils.setPrimitiveElementMap[it]!! }
            ?: reflectionUtils.setElement
        return listOf("${setElementMethod.name}(${owner.name}, ${call.index.stackName}, ${call.value.stackName})")
    }
}
