/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.transformer

import kim.der.asm.api.annotation.*
import kim.der.asm.api.replace.RedirectionReplace
import kim.der.asm.api.replace.RedirectionReplaceApi
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AsmInjectorFactory
import kim.der.asm.injector.util.InlineCodeGenerator
import kim.der.asm.utils.transformer.BytecodeUtil
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 目标类改写上下文。
 *
 * 该上下文负责把单个 ASM 类（[AsmInfo.asmClass]）解析为注入/替换/重定向等操作，
 * 并将这些操作应用到目标 [ClassNode] 上。
 *
 * ## 处理顺序
 *
 * 该上下文会按固定顺序处理注入点，以尽量贴近 Mixin 的行为并避免注入之间互相干扰：
 *
 * 1. RETURN 与 TAIL 注入
 * 2. 其他非 HEAD 注入（如 INVOKE）
 * 3. 最后处理 HEAD 注入
 *
 * 以上顺序用于避免 HEAD 注入创建的 RETURN 指令被 RETURN 注入二次处理，导致“取消分支仍触发 RETURN 注入”这类行为偏差。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class TargetClassContext(
    private val className: String,
    private val classNode: ClassNode,
    private val asmInfo: AsmInfo,
) {
    /**
     * 应用 ASM 到目标类。
     *
     * @return 如果至少一个改写生效则返回 true
     */
    fun applyAsm(): Boolean {
        var transformed = false

        // 检查是否需要为 class 类型的 Mixin 创建静态字段
        ensureSingletonField()

        // 检查是否有 @AddInterface 注解
        val addInterfaceAnnotation = asmInfo.asmClass.getAnnotation(AddInterface::class.java)
        if (addInterfaceAnnotation != null) {
            if (applyAddInterface(addInterfaceAnnotation)) {
                transformed = true
            }
        }

        // 检查是否有 @RemoveInterface 注解
        val removeInterfaceAnnotation = asmInfo.asmClass.getAnnotation(RemoveInterface::class.java)
        if (removeInterfaceAnnotation != null) {
            if (applyRemoveInterface(removeInterfaceAnnotation)) {
                transformed = true
            }
        }

        // 检查是否有 @ReplaceAllMethods 注解
        val replaceAllAnnotation = asmInfo.asmClass.getAnnotation(ReplaceAllMethods::class.java)
        if (replaceAllAnnotation != null) {
            if (applyReplaceAllMethods(replaceAllAnnotation)) {
                transformed = true
            }
        }

        // 检查是否有 @RedirectAllMethods 注解
        val redirectAllAnnotation = asmInfo.asmClass.getAnnotation(RedirectAllMethods::class.java)
        if (redirectAllAnnotation != null) {
            if (applyRedirectAllMethods(redirectAllAnnotation)) {
                transformed = true
            }
        }

        // 处理 ASM 类中的所有字段
        if (applyFields()) {
            transformed = true
        }

        // 收集所有需要处理的方法，按注入点类型分组
        // 注意：必须按照特定的顺序处理注入，以确保行为一致
        val methodsToProcess = asmInfo.asmClass.declaredMethods.toList()
        val copyMethodNames = buildCopyMethodNames(methodsToProcess)

        // 第一轮：处理 RETURN 和 TAIL 注入（必须在 HEAD 注入之前）
        //
        // 原因：
        // 1. HEAD 注入如果取消方法执行，会在取消分支中创建一个新的 RETURN 指令
        // 2. RETURN 注入会查找所有 RETURN 指令并在其之前插入代码
        // 3. 如果 RETURN 注入在 HEAD 注入之后执行，会错误地在 HEAD 注入创建的 RETURN 指令之前插入代码
        // 4. 这会导致即使 HEAD 注入取消了方法，RETURN 注入仍然会被执行（不符合预期）
        //
        // 解决方案：
        // - 先处理 RETURN/TAIL 注入，它们只会在原始的 RETURN 指令之前插入代码
        // - 后处理 HEAD 注入，HEAD 注入创建的 RETURN 指令不会被 RETURN 注入处理
        // - 这样确保：当 HEAD 注入取消方法时，RETURN 注入不会被执行（贴近 Mixin-master 的行为）
        for (method in methodsToProcess) {
            val injectAnnotation = method.getAnnotation(AsmInject::class.java)
            if (injectAnnotation != null &&
                (injectAnnotation.target == InjectionPoint.RETURN || injectAnnotation.target == InjectionPoint.TAIL)
            ) {
                if (applyInject(method, injectAnnotation, copyMethodNames)) {
                    transformed = true
                }
            }
        }

        // 处理其他非 HEAD 注入（如 INVOKE 等）
        // 这些注入不涉及 RETURN 指令的创建，可以在 HEAD 注入之前或之后处理
        for (method in methodsToProcess) {
            val injectAnnotation = method.getAnnotation(AsmInject::class.java)
            val modifyArgAnnotation = method.getAnnotation(ModifyArg::class.java)
            val modifyArgsAnnotation = method.getAnnotation(ModifyArgs::class.java)
            val modifyReceiverAnnotation = method.getAnnotation(ModifyReceiver::class.java)
            val wrapOperationAnnotation = method.getAnnotation(WrapOperation::class.java)
            val wrapMethodAnnotation = method.getAnnotation(WrapMethod::class.java)
            val wrapWithConditionAnnotation = method.getAnnotation(WrapWithCondition::class.java)
            val modifyExpressionValueAnnotation = method.getAnnotation(ModifyExpressionValue::class.java)
            val modifyVariableAnnotation = method.getAnnotation(ModifyVariable::class.java)
            val redirectAnnotation = method.getAnnotation(Redirect::class.java)
            val overwriteAnnotation = method.getAnnotation(Overwrite::class.java)
            val copyAnnotation = method.getAnnotation(Copy::class.java)
            val modifyReturnValueAnnotation = method.getAnnotation(ModifyReturnValue::class.java)
            val modifyConstantAnnotation = method.getAnnotation(ModifyConstant::class.java)
            val removeFieldAnnotation = method.getAnnotation(RemoveField::class.java)
            val removeMethodAnnotation = method.getAnnotation(RemoveMethod::class.java)
            val removeSynchronizedAnnotation = method.getAnnotation(RemoveSynchronized::class.java)
            val accessorAnnotation = method.getAnnotation(Accessor::class.java)
            val invokerAnnotation = method.getAnnotation(Invoker::class.java)
            val shadowAnnotation = method.getAnnotation(Shadow::class.java)

            when {
                injectAnnotation != null -> {
                    // HEAD 注入在下一轮处理（确保在 RETURN 注入之后）
                    // RETURN 和 TAIL 注入已在第一轮处理完成
                    if (injectAnnotation.target != InjectionPoint.HEAD &&
                        injectAnnotation.target != InjectionPoint.RETURN &&
                        injectAnnotation.target != InjectionPoint.TAIL
                    ) {
                        if (applyInject(method, injectAnnotation, copyMethodNames)) {
                            transformed = true
                        }
                    }
                }
                modifyArgAnnotation != null -> {
                    if (applyModifyArg(method, modifyArgAnnotation)) {
                        transformed = true
                    }
                }
                modifyArgsAnnotation != null -> {
                    if (applyModifyArgs(method, modifyArgsAnnotation)) {
                        transformed = true
                    }
                }
                modifyReceiverAnnotation != null -> {
                    if (applyModifyReceiver(method, modifyReceiverAnnotation)) {
                        transformed = true
                    }
                }
                wrapOperationAnnotation != null -> {
                    if (applyWrapOperation(method, wrapOperationAnnotation)) {
                        transformed = true
                    }
                }
                wrapMethodAnnotation != null -> {
                    if (applyWrapMethod(method, wrapMethodAnnotation)) {
                        transformed = true
                    }
                }
                wrapWithConditionAnnotation != null -> {
                    if (applyWrapWithCondition(method, wrapWithConditionAnnotation)) {
                        transformed = true
                    }
                }
                modifyExpressionValueAnnotation != null -> {
                    if (applyModifyExpressionValue(method, modifyExpressionValueAnnotation)) {
                        transformed = true
                    }
                }
                modifyVariableAnnotation != null -> {
                    if (applyModifyVariable(method, modifyVariableAnnotation)) {
                        transformed = true
                    }
                }
                redirectAnnotation != null && redirectAllAnnotation == null -> {
                    if (applyRedirect(method, redirectAnnotation)) {
                        transformed = true
                    }
                }
                overwriteAnnotation != null -> {
                    if (applyOverwrite(method, overwriteAnnotation, copyMethodNames)) {
                        transformed = true
                    }
                }
                copyAnnotation != null -> {
                    if (applyCopy(method, copyAnnotation, copyMethodNames)) {
                        transformed = true
                    }
                }
                modifyReturnValueAnnotation != null -> {
                    if (applyModifyReturnValue(method, modifyReturnValueAnnotation)) {
                        transformed = true
                    }
                }
                modifyConstantAnnotation != null -> {
                    if (applyModifyConstant(method, modifyConstantAnnotation)) {
                        transformed = true
                    }
                }
                removeFieldAnnotation != null -> {
                    if (applyRemoveField(method, removeFieldAnnotation)) {
                        transformed = true
                    }
                }
                removeMethodAnnotation != null -> {
                    if (applyRemoveMethod(method, removeMethodAnnotation)) {
                        transformed = true
                    }
                }
                removeSynchronizedAnnotation != null -> {
                    if (applyRemoveSynchronized(method, removeSynchronizedAnnotation)) {
                        transformed = true
                    }
                }
                accessorAnnotation != null -> {
                    if (applyAccessor(method, accessorAnnotation)) {
                        transformed = true
                    }
                }
                invokerAnnotation != null -> {
                    if (applyInvoker(method, invokerAnnotation)) {
                        transformed = true
                    }
                }
                shadowAnnotation != null -> {
                    // Shadow 方法只需要验证，不需要转换
                    applyShadowMethod(method, shadowAnnotation)
                }
            }
        }

        // 第二轮：最后处理 HEAD 注入（确保在 RETURN 注入之后）
        //
        // 处理顺序的重要性：
        // - HEAD 注入在方法开头插入代码，如果取消方法执行，会在取消分支中创建 RETURN 指令
        // - RETURN 注入已在第一轮处理完成，只会处理当时存在的原始 RETURN 指令
        // - HEAD 注入创建的 RETURN 指令不会被 RETURN 注入处理
        // - 这确保了当 HEAD 注入取消方法时，RETURN 注入不会被执行（符合预期行为）
        //
        // 参考 Mixin-master 的行为：
        // - 当 HEAD 注入取消方法时，方法立即返回，不会执行方法体
        // - 因此 RETURN 注入不应该被执行（因为方法体从未执行）
        for (method in methodsToProcess) {
            val injectAnnotation = method.getAnnotation(AsmInject::class.java)
            if (injectAnnotation != null && injectAnnotation.target == InjectionPoint.HEAD) {
                if (applyInject(method, injectAnnotation, copyMethodNames)) {
                    transformed = true
                }
            }
        }

        return transformed
    }

    /**
     * 确保为 class 类型的 Mixin 创建单例静态字段
     * 参考 Mixin-master 的实现，为需要实例调用的 Mixin 方法创建静态字段缓存实例
     */
    private fun ensureSingletonField() {
        // 检查是否是 Kotlin object（有 INSTANCE 字段）
        val isKotlinObject =
            try {
                asmInfo.asmClass.getDeclaredField("INSTANCE")
                true
            } catch (e: NoSuchFieldException) {
                false
            }

        // 如果是 Kotlin object，不需要创建静态字段
        if (isKotlinObject) {
            return
        }

        // 检查是否有非静态方法需要实例调用
        val hasNonStaticMethod =
            asmInfo.asmClass.declaredMethods.any { method ->
                val isStatic = (method.modifiers and java.lang.reflect.Modifier.STATIC) != 0
                val hasAnnotation =
                        method.getAnnotation(AsmInject::class.java) != null ||
                        method.getAnnotation(ModifyArg::class.java) != null ||
                        method.getAnnotation(ModifyArgs::class.java) != null ||
                        method.getAnnotation(ModifyReceiver::class.java) != null ||
                        method.getAnnotation(WrapOperation::class.java) != null ||
                        method.getAnnotation(WrapMethod::class.java) != null ||
                        method.getAnnotation(WrapWithCondition::class.java) != null ||
                        method.getAnnotation(ModifyExpressionValue::class.java) != null ||
                        method.getAnnotation(ModifyVariable::class.java) != null ||
                        method.getAnnotation(Redirect::class.java) != null ||
                        method.getAnnotation(ModifyReturnValue::class.java) != null ||
                        method.getAnnotation(ModifyConstant::class.java) != null
                !isStatic && hasAnnotation
            }

        // 如果没有非静态方法，不需要创建静态字段
        if (!hasNonStaticMethod) {
            return
        }

        // 创建静态字段名
        val singletonFieldName = "\$asmInstance\$${asmInfo.asmClass.simpleName}"
        val mixinType = Type.getType(asmInfo.asmClass)
        val singletonFieldDesc = "L${mixinType.internalName};"

        // 检查字段是否已存在
        val existingField = classNode.fields.find { it.name == singletonFieldName }
        if (existingField != null) {
            return // 字段已存在
        }

        // 创建静态字段
        val fieldNode =
            FieldNode(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
                singletonFieldName,
                singletonFieldDesc,
                null,
                null,
            )
        classNode.fields.add(fieldNode)
    }

    /**
     * 应用字段处理（Shadow, Mutable, Final）
     */
    private fun applyFields(): Boolean {
        var transformed = false

        for (field in asmInfo.asmClass.declaredFields) {
            val shadowAnnotation = field.getAnnotation(Shadow::class.java)
            val mutableAnnotation = field.getAnnotation(Mutable::class.java)
            val finalAnnotation = field.getAnnotation(Final::class.java)
            val addFieldAnnotation = field.getAnnotation(AddField::class.java)
            val removeFieldAnnotation = field.getAnnotation(RemoveField::class.java)

            if (addFieldAnnotation != null) {
                if (applyAddField(field, addFieldAnnotation)) {
                    transformed = true
                }
                continue
            }

            if (removeFieldAnnotation != null) {
                if (applyRemoveField(field, removeFieldAnnotation)) {
                    transformed = true
                }
                continue
            }

            if (shadowAnnotation != null) {
                if (applyShadowField(field, shadowAnnotation)) {
                    transformed = true
                }
            }

            if (mutableAnnotation != null || finalAnnotation != null) {
                if (applyFieldModifiers(field, mutableAnnotation != null, finalAnnotation != null)) {
                    transformed = true
                }
            }
        }

        return transformed
    }

    /**
     * 应用 @AddField 添加字段。
     */
    private fun applyAddField(
        field: java.lang.reflect.Field,
        annotation: AddField,
    ): Boolean {
        val targetFieldName = annotation.field.ifEmpty { field.name }
        if (classNode.fields.any { it.name == targetFieldName }) {
            return false
        }

        classNode.fields.add(
            FieldNode(
                toFieldAccess(field),
                targetFieldName,
                Type.getDescriptor(field.type),
                null,
                null,
            ),
        )
        return true
    }

    private fun toFieldAccess(field: java.lang.reflect.Field): Int {
        var access = field.modifiers and (
            Modifier.PUBLIC or
                Modifier.PRIVATE or
                Modifier.PROTECTED or
                Modifier.STATIC or
                Modifier.FINAL or
                Modifier.VOLATILE or
                Modifier.TRANSIENT
        )

        if (field.isSynthetic) {
            access = access or Opcodes.ACC_SYNTHETIC
        }
        if (field.isEnumConstant) {
            access = access or Opcodes.ACC_ENUM
        }

        return access
    }

    /**
     * 应用 Shadow 字段
     */
    private fun applyShadowField(
        field: java.lang.reflect.Field,
        annotation: Shadow,
    ): Boolean {
        val fieldName = field.name
        val targetFieldName = resolveShadowTargetName(annotation.method, fieldName)

        // 查找目标字段
        val targetField = findAccessorTargetField(targetFieldName)
            ?: throw IllegalStateException("Shadow field $targetFieldName not found in $className")

        val shadowFieldType = Type.getType(field.type)
        val targetFieldType = Type.getType(targetField.field.desc)
        if (shadowFieldType != targetFieldType) {
            throw IllegalStateException(
                "Shadow field $targetFieldName type ($shadowFieldType) must match target field type ($targetFieldType)",
            )
        }

        // 如果字段存在，应用 Mutable 修饰符
        if (field.isAnnotationPresent(Mutable::class.java) && targetField.owner == className) {
            val originalAccess = targetField.field.access
            targetField.field.access = targetField.field.access and Opcodes.ACC_FINAL.inv()
            return targetField.field.access != originalAccess
        }

        return false
    }

    /**
     * 应用 Shadow 方法
     */
    private fun applyShadowMethod(
        method: Method,
        annotation: Shadow,
    ) {
        val methodName = method.name
        val targetMethodName = resolveShadowTargetName(annotation.method, methodName)

        // 验证目标方法是否存在（Shadow 方法只是引用，不需要转换）
        val targetMethod = findTargetMethod("$targetMethodName${Type.getMethodDescriptor(method)}")
        if (targetMethod == null) {
            throw IllegalStateException("Shadow method $targetMethodName${Type.getMethodDescriptor(method)} not found in $className")
        }
    }

    private fun resolveShadowTargetName(
        declaredName: String,
        memberName: String,
    ): String =
        when {
            declaredName.isEmpty() -> memberName
            declaredName.startsWith(Shadow.prefix) -> declaredName.substring(Shadow.prefix.length)
            else -> declaredName
        }

    /**
     * 应用字段修饰符
     */
    private fun applyFieldModifiers(
        field: java.lang.reflect.Field,
        mutable: Boolean,
        final: Boolean,
    ): Boolean {
        val targetField = classNode.fields.find { it.name == field.name }
        var transformed = false

        if (targetField != null) {
            if (mutable) {
                // 移除 FINAL 标志
                val originalAccess = targetField.access
                targetField.access = targetField.access and Opcodes.ACC_FINAL.inv()
                transformed = transformed || targetField.access != originalAccess
            }
            if (final) {
                // 添加 FINAL 标志
                val originalAccess = targetField.access
                targetField.access = targetField.access or Opcodes.ACC_FINAL
                transformed = transformed || targetField.access != originalAccess
            }
        }

        return transformed
    }

    /**
     * 应用 @ModifyConstant 修改常量
     */
    private fun applyModifyConstant(
        method: Method,
        annotation: ModifyConstant,
    ): Boolean {
        val (targetMethod, methodSignature) = resolveModifyConstantTargetMethod(method, annotation)
        val injector =
            AsmInjectorFactory.createModifyConstantInjector(
                method,
                asmInfo,
                if (annotation.constant.isEmpty()) null else annotation.constant,
                annotation.ordinal,
                annotation.slice,
            )
        val injectionCount = injector.injectCount(targetMethod)
        if (annotation.require > 0 || annotation.allow >= 0 || annotation.expect != 1) {
            return requireModifyConstantCount(
                injectionCount,
                annotation,
                method,
                methodSignature,
            )
        }
        if (annotation.constant.isEmpty()) {
            return injectionCount > 0
        }
        return requireInjectorMatched(
            injectionCount > 0,
            "@ModifyConstant",
            method,
            methodSignature,
        )
    }

    private fun resolveModifyConstantTargetMethod(
        method: Method,
        annotation: ModifyConstant,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    hasCompatibleModifyConstantCandidate(method, annotation, candidate)
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(method.name))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@ModifyConstant handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun hasCompatibleModifyConstantCandidate(
        handlerMethod: Method,
        annotation: ModifyConstant,
        targetMethod: MethodNode,
    ): Boolean =
        runCatching {
            val candidateTypes = collectModifyConstantCandidateTypes(targetMethod, annotation)
            candidateTypes.any { candidate ->
                validateModifyConstantHandlerSignature(handlerMethod, targetMethod, candidate.type, candidate.nullConstant)
            }
        }.getOrDefault(false)

    private fun collectModifyConstantCandidateTypes(
        targetMethod: MethodNode,
        annotation: ModifyConstant,
    ): List<ModifyConstantCandidate> {
        val requestedValue = annotation.constant.ifEmpty { null }
        val insns = targetMethod.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveModifyConstantSliceRange(insns, annotation.slice)
        val candidates = mutableListOf<ModifyConstantCandidate>()

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (!BytecodeUtil.isConstant(insn)) {
                continue
            }
            if (requestedValue != null && !matchesModifyConstantValue(insn, BytecodeUtil.getConstant(insn), requestedValue)) {
                continue
            }
            val constantType = resolveModifyConstantType(insn, requestedValue) ?: continue
            candidates.add(ModifyConstantCandidate(constantType, BytecodeUtil.getConstant(insn) == null))
        }

        return candidates
    }

    private fun resolveModifyConstantSliceRange(
        insns: Array<AbstractInsnNode>,
        slice: Slice,
    ): Pair<Int, Int> {
        val startIndex =
            if (slice.from.target.isNotEmpty()) {
                val fromIndex = findModifyConstantSliceBoundaryIndex(insns, slice.from, 0) ?: return insns.size to insns.size
                fromIndex + 1
            } else {
                0
            }
        val endIndex =
            if (slice.to.target.isNotEmpty()) {
                findModifyConstantSliceBoundaryIndex(insns, slice.to, startIndex) ?: return insns.size to insns.size
            } else {
                insns.size
            }
        return startIndex to endIndex.coerceAtLeast(startIndex)
    }

    private fun findModifyConstantSliceBoundaryIndex(
        insns: Array<AbstractInsnNode>,
        at: At,
        startIndex: Int,
    ): Int? {
        require(at.value == InjectionPoint.INVOKE) {
            "Only INVOKE slice boundaries are supported for @ModifyConstant: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseModifyConstantTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyConstant slice boundary method signature: ${at.target} " +
                    "(parsed: owner=$boundaryOwner, name=$boundaryName, desc=$boundaryDesc)",
            )
        }

        for (index in startIndex until insns.size) {
            val insn = insns[index]
            if (insn is MethodInsnNode && matchesModifyConstantTargetMethod(insn, boundaryOwner, boundaryName, boundaryDesc)) {
                return index
            }
        }

        return null
    }

    private fun parseModifyConstantTargetMethod(signature: String): Triple<String?, String?, String?> {
        if (signature.isEmpty()) {
            return Triple(null, null, null)
        }

        val parenIndex = signature.indexOf('(')
        if (parenIndex < 0) {
            return Triple(null, signature, null)
        }

        val ownerAndName = signature.substring(0, parenIndex)
        val desc = signature.substring(parenIndex)
        val slashIndex = ownerAndName.lastIndexOf('/')
        val dotIndex = ownerAndName.lastIndexOf('.')
        val separatorIndex = maxOf(slashIndex, dotIndex)

        return if (separatorIndex >= 0) {
            Triple(
                ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                ownerAndName.substring(separatorIndex + 1),
                desc,
            )
        } else {
            Triple(null, ownerAndName, desc)
        }
    }

    private fun matchesModifyConstantTargetMethod(
        insn: MethodInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String?,
    ): Boolean {
        if (targetOwner != null && insn.owner != targetOwner) {
            return false
        }
        if (insn.name != targetName) {
            return false
        }
        return targetDesc == null || insn.desc == targetDesc
    }

    private fun matchesModifyConstantValue(
        insn: AbstractInsnNode,
        constant: Any?,
        value: String,
    ): Boolean {
        if (constant == null) {
            return value == "null"
        }
        if (value == "true" || value == "false") {
            return isModifyConstantBooleanInsn(insn, value == "true")
        }

        return when (constant) {
            is Int -> constant.toString() == value
            is Long -> constant.toString() == value
            is Float -> constant.toString() == value
            is Double -> constant.toString() == value
            is String -> constant == value
            is Type -> {
                if (constant.sort == Type.METHOD) {
                    constant.descriptor == value
                } else {
                    constant.internalName == value.replace('.', '/')
                }
            }
            is Handle -> "${constant.owner}.${constant.name}${constant.desc}" == value
            is ConstantDynamic -> constant.name == value || "${constant.name}:${constant.descriptor}" == value
            else -> false
        }
    }

    private fun isModifyConstantBooleanInsn(
        insn: AbstractInsnNode,
        expected: Boolean,
    ): Boolean =
        (expected && insn.opcode == Opcodes.ICONST_1) ||
            (!expected && insn.opcode == Opcodes.ICONST_0)

    private fun resolveModifyConstantType(
        insn: AbstractInsnNode,
        requestedValue: String?,
    ): Type? {
        if (requestedValue != null &&
            (requestedValue == "true" || requestedValue == "false") &&
            isModifyConstantBooleanInsn(insn, requestedValue == "true")
        ) {
            return Type.BOOLEAN_TYPE
        }
        return BytecodeUtil.getConstantType(insn)
    }

    private fun validateModifyConstantHandlerSignature(
        handlerMethod: Method,
        targetMethod: MethodNode,
        constantType: Type,
        nullConstant: Boolean,
    ): Boolean {
        val handlerParamTypes = Type.getArgumentTypes(handlerMethod)
        if (handlerParamTypes.isEmpty()) {
            return false
        }

        val acceptsConstant =
            if (nullConstant) {
                handlerParamTypes[0].isReferenceType()
            } else {
                isModifyConstantParameterCompatible(constantType, handlerParamTypes[0])
            }
        if (!acceptsConstant) {
            return false
        }

        val handlerReturnType = Type.getReturnType(handlerMethod)
        if (!isModifyConstantReturnCompatible(constantType, handlerReturnType, handlerMethod.returnType)) {
            return false
        }

        val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)
        val requestedTargetParamCount = handlerParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            return false
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = handlerParamTypes[index + 1]
            if (!isModifyConstantParameterCompatible(expected, actual)) {
                return false
            }
        }

        return true
    }

    private fun isModifyConstantParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) {
            return true
        }
        if (!expected.isReferenceType() || !actual.isReferenceType()) {
            return false
        }
        if (actual.sort == Type.OBJECT &&
            (actual.internalName == "java/lang/Object" || actual.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val expectedClass = loadReferenceClass(expected)
            loadReferenceClass(actual).isAssignableFrom(expectedClass)
        }.getOrDefault(false)
    }

    private fun isModifyConstantReturnCompatible(
        constantType: Type,
        handlerReturnType: Type,
        handlerReturnClass: Class<*>,
    ): Boolean {
        if (constantType == handlerReturnType) {
            return true
        }
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (!constantType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val constantClass = loadReferenceClass(constantType)
            constantClass.isAssignableFrom(handlerReturnClass)
        }.getOrDefault(false)
    }

    private data class ModifyConstantCandidate(
        val type: Type,
        val nullConstant: Boolean,
    )

    /**
     * 应用 @Accessor 访问器
     */
    private fun applyAccessor(
        method: Method,
        annotation: Accessor,
    ): Boolean {
        val targetFieldName =
            if (annotation.value.isEmpty()) {
                // 从方法名推断字段名
                inferFieldNameFromMethod(method.name)
            } else {
                annotation.value
            }

        val targetField =
            findAccessorTargetField(targetFieldName)
                ?: throw IllegalStateException("Accessor target field $targetFieldName not found in $className")

        // 生成访问器方法
        val accessorMethod = generateAccessorMethod(method, targetField, targetFieldName)
        ensureGeneratedMethodAbsent(accessorMethod.name, accessorMethod.desc)
        classNode.methods.add(accessorMethod)

        return true
    }

    private fun findAccessorTargetField(fieldName: String): AccessorTargetField? {
        classNode.fields.find { it.name == fieldName }?.let {
            return AccessorTargetField(className, it)
        }
        return findInheritedAccessorTargetField(fieldName)
    }

    private fun findInheritedAccessorTargetField(fieldName: String): AccessorTargetField? {
        var parentName = classNode.superName
        while (parentName != null && parentName != "java/lang/Object") {
            val parentClass = loadParentClass(parentName) ?: return null
            parentClass.fields.find { it.name == fieldName && isAccessorInheritedFieldVisible(it) }?.let {
                return AccessorTargetField(parentClass.name, it)
            }
            parentName = parentClass.superName
        }
        return null
    }

    private fun isAccessorInheritedFieldVisible(field: FieldNode): Boolean =
        (field.access and Opcodes.ACC_PRIVATE) == 0

    /**
     * 应用 @Invoker 调用器
     */
    private fun applyInvoker(
        method: Method,
        annotation: Invoker,
    ): Boolean {
        val targetMethodName =
            if (annotation.value.isEmpty()) {
                // 从方法名推断目标方法名
                inferMethodNameFromInvoker(method.name)
            } else {
                annotation.value
            }

        val targetMethod =
            if (targetMethodName == "<init>") {
                val constructorSignature = buildConstructorSignature(method)
                findTargetMethod(constructorSignature)
                    ?: throw IllegalStateException("Invoker target constructor $constructorSignature not found in $className")
            } else {
                findTargetMethod("$targetMethodName${Type.getMethodDescriptor(method)}")
                    ?: throw IllegalStateException("Invoker target method $targetMethodName not found in $className")
            }

        // 生成调用器方法
        val invokerMethod = generateInvokerMethod(method, targetMethod, targetMethodName)
        ensureGeneratedMethodAbsent(invokerMethod.name, invokerMethod.desc)
        classNode.methods.add(invokerMethod)

        return true
    }

    /**
     * 从方法名推断字段名
     */
    private fun inferFieldNameFromMethod(methodName: String): String {
        // 支持 get/set/is 前缀
        return when {
            methodName.startsWith("get") && methodName.length > 3 -> {
                methodName.substring(3).let { it[0].lowercaseChar() + it.substring(1) }
            }
            methodName.startsWith("set") && methodName.length > 3 -> {
                methodName.substring(3).let { it[0].lowercaseChar() + it.substring(1) }
            }
            methodName.startsWith("is") && methodName.length > 2 -> {
                methodName.substring(2).let { it[0].lowercaseChar() + it.substring(1) }
            }
            else -> methodName
        }
    }

    /**
     * 从调用器方法名推断目标方法名
     */
    private fun inferMethodNameFromInvoker(methodName: String): String {
        // 支持 call/invoke 前缀
        return when {
            methodName.startsWith("call") && methodName.length > 4 -> {
                methodName.substring(4).let { it[0].lowercaseChar() + it.substring(1) }
            }
            methodName.startsWith("invoke") && methodName.length > 6 -> {
                methodName.substring(6).let { it[0].lowercaseChar() + it.substring(1) }
            }
            else -> methodName
        }
    }

    /**
     * 生成访问器方法
     */
    private fun generateAccessorMethod(
        asmMethod: Method,
        targetField: AccessorTargetField,
        fieldName: String,
    ): MethodNode {
        val isGetter = asmMethod.parameterCount == 0
        val fieldNode = targetField.field
        val isStatic = (fieldNode.access and Opcodes.ACC_STATIC) != 0
        val fieldType = Type.getType(fieldNode.desc)
        val asmMethodStatic =
            java.lang.reflect.Modifier
                .isStatic(asmMethod.modifiers)

        // 验证访问器方法签名
        if (isGetter) {
            // Getter 必须返回字段类型且无参数
            val returnType = Type.getReturnType(asmMethod)
            if (returnType != fieldType) {
                throw IllegalStateException("Accessor getter return type ($returnType) must match field type ($fieldType)")
            }
        } else {
            // Setter 必须返回 void 且有一个参数
            val returnType = Type.getReturnType(asmMethod)
            if (returnType != Type.VOID_TYPE) {
                throw IllegalStateException("Accessor setter must return void")
            }
            if (asmMethod.parameterCount != 1) {
                throw IllegalStateException("Accessor setter must take exactly 1 argument")
            }
            val paramType = Type.getType(asmMethod.parameterTypes[0])
            if (paramType != fieldType) {
                throw IllegalStateException("Accessor setter parameter type ($paramType) must match field type ($fieldType)")
            }
        }

        // 检查静态性匹配
        if (asmMethodStatic != isStatic) {
            throw IllegalStateException("Accessor method static modifier must match target field static modifier")
        }

        val methodNode =
            MethodNode(
                (Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC) or (if (isStatic) Opcodes.ACC_STATIC else 0),
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                null,
                null,
            )

        val il = InsnList()

        if (isGetter) {
            // Getter: 加载字段值并返回
            if (isStatic) {
                il.add(FieldInsnNode(Opcodes.GETSTATIC, targetField.owner, fieldName, fieldNode.desc))
            } else {
                il.add(VarInsnNode(Opcodes.ALOAD, 0))
                il.add(FieldInsnNode(Opcodes.GETFIELD, targetField.owner, fieldName, fieldNode.desc))
            }
            il.add(InstructionUtil.makeReturn(fieldType))
        } else {
            // Setter: 接收参数并设置字段值
            // 如果字段是 final，需要先移除 final 标志（如果访问器方法有 @Mutable 注解）
            val mutable = asmMethod.isAnnotationPresent(Mutable::class.java)
            if (mutable && targetField.owner == className && (fieldNode.access and Opcodes.ACC_FINAL) != 0) {
                fieldNode.access = fieldNode.access and Opcodes.ACC_FINAL.inv()
            }

            if (isStatic) {
                il.add(InstructionUtil.loadParam(fieldType, 0))
                il.add(FieldInsnNode(Opcodes.PUTSTATIC, targetField.owner, fieldName, fieldNode.desc))
            } else {
                il.add(VarInsnNode(Opcodes.ALOAD, 0))
                il.add(InstructionUtil.loadParam(fieldType, 1))
                il.add(FieldInsnNode(Opcodes.PUTFIELD, targetField.owner, fieldName, fieldNode.desc))
            }
            il.add(InsnNode(Opcodes.RETURN))
        }

        methodNode.instructions = il
        val fieldSize = if (fieldType.sort == Type.LONG || fieldType.sort == Type.DOUBLE) 2 else 1
        methodNode.maxLocals = fieldSize + (if (isStatic) 0 else 1)
        methodNode.maxStack = fieldSize + (if (isStatic) 0 else 1)

        return methodNode
    }

    private data class AccessorTargetField(
        val owner: String,
        val field: FieldNode,
    )

    /**
     * 生成调用器方法
     */
    private fun generateInvokerMethod(
        asmMethod: Method,
        targetMethod: MethodNode,
        methodName: String,
    ): MethodNode {
        if (methodName == "<init>") {
            return generateConstructorInvokerMethod(asmMethod, targetMethod)
        }

        val isStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0
        val asmMethodStatic =
            java.lang.reflect.Modifier
                .isStatic(asmMethod.modifiers)
        val paramTypes = Type.getArgumentTypes(targetMethod.desc)
        val returnType = Type.getReturnType(targetMethod.desc)
        val isInterface = (classNode.access and Opcodes.ACC_INTERFACE) != 0
        val isPrivate = (targetMethod.access and Opcodes.ACC_PRIVATE) != 0
        val isSynthetic = (targetMethod.access and Opcodes.ACC_SYNTHETIC) != 0

        // 验证方法签名匹配
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        val asmReturnType = Type.getReturnType(asmMethod)
        if (!asmParamTypes.contentEquals(paramTypes) || asmReturnType != returnType) {
            throw IllegalStateException("Invoker method signature must match target method signature")
        }

        // 检查静态性匹配（构造函数除外）
        if (methodName != "<init>" && asmMethodStatic != isStatic) {
            throw IllegalStateException("Invoker method static modifier must match target method static modifier")
        }

        val methodNode =
            MethodNode(
                (Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC) or (if (asmMethodStatic) Opcodes.ACC_STATIC else 0),
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                null,
                null,
            )

        val il = InsnList()

        // 加载参数
        if (!isStatic && methodName != "<init>") {
            il.add(VarInsnNode(Opcodes.ALOAD, 0))
        }

        var varIndex = if (isStatic || methodName == "<init>") 0 else 1
        for (paramType in paramTypes) {
            il.add(InstructionUtil.loadParam(paramType, varIndex))
            varIndex += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
        }

        // 调用目标方法
        val opcode =
            when {
                isStatic -> Opcodes.INVOKESTATIC
                methodName == "<init>" -> Opcodes.INVOKESPECIAL
                isInterface -> {
                    if (isPrivate) Opcodes.INVOKESPECIAL else Opcodes.INVOKEINTERFACE
                }
                isPrivate -> Opcodes.INVOKESPECIAL
                else -> Opcodes.INVOKEVIRTUAL
            }

        il.add(
            MethodInsnNode(
                opcode,
                className,
                methodName,
                targetMethod.desc,
                isInterface,
            ),
        )

        // 返回
        il.add(InstructionUtil.makeReturn(returnType))

        methodNode.instructions = il
        methodNode.maxLocals = varIndex
        // 计算 maxStack: 参数 + 实例（如果不是静态） + 返回值（如果不是 void）
        var maxStack =
            paramTypes.sumOf { if (it.sort == Type.LONG || it.sort == Type.DOUBLE) 2 else 1 } +
                (if (isStatic || methodName == "<init>") 0 else 1)
        if (returnType != Type.VOID_TYPE) {
            maxStack += if (returnType.sort == Type.LONG || returnType.sort == Type.DOUBLE) 2 else 1
        }
        methodNode.maxStack = maxStack

        return methodNode
    }

    /**
     * 构建构造器调用器目标签名。
     *
     * `@Invoker("<init>")` 的 ASM 方法返回类型用于生成工厂方法签名，目标构造器本身始终返回 `void`。
     */
    private fun buildConstructorSignature(asmMethod: Method): String {
        val argumentDesc = Type.getArgumentTypes(asmMethod).joinToString(separator = "") { it.descriptor }
        return "<init>($argumentDesc)V"
    }

    /**
     * 生成构造器调用器方法。
     *
     * 构造器调用器会在目标类中生成一个静态工厂方法，创建目标类实例并调用匹配构造器。
     */
    private fun generateConstructorInvokerMethod(
        asmMethod: Method,
        targetMethod: MethodNode,
    ): MethodNode {
        if (!java.lang.reflect.Modifier.isStatic(asmMethod.modifiers)) {
            throw IllegalStateException("Constructor invoker method must be static")
        }

        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        val constructorParamTypes = Type.getArgumentTypes(targetMethod.desc)
        if (!asmParamTypes.contentEquals(constructorParamTypes)) {
            throw IllegalStateException("Constructor invoker parameters must match target constructor parameters")
        }

        val returnType = Type.getReturnType(asmMethod)
        if (!isConstructorInvokerReturnTypeAllowed(returnType)) {
            throw IllegalStateException("Constructor invoker return type must be $className, a declared supertype, or java/lang/Object")
        }

        val methodNode =
            MethodNode(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                null,
                null,
            )
        val il = InsnList()
        il.add(TypeInsnNode(Opcodes.NEW, className))
        il.add(InsnNode(Opcodes.DUP))

        var varIndex = 0
        for (paramType in constructorParamTypes) {
            il.add(InstructionUtil.loadParam(paramType, varIndex))
            varIndex += paramType.size
        }

        il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, className, "<init>", targetMethod.desc, false))
        il.add(InsnNode(Opcodes.ARETURN))

        methodNode.instructions = il
        methodNode.maxLocals = varIndex
        methodNode.maxStack = 2 + constructorParamTypes.sumOf { it.size }

        return methodNode
    }

    private fun isConstructorInvokerReturnTypeAllowed(returnType: Type): Boolean {
        if (returnType.sort != Type.OBJECT) {
            return false
        }
        if (returnType.internalName == className ||
            returnType.internalName == "java/lang/Object" ||
            returnType.internalName == classNode.superName ||
            classNode.interfaces.contains(returnType.internalName)
        ) {
            return true
        }
        return runCatching {
            val returnClass = loadReferenceClass(returnType)
            constructorInvokerDeclaredSupertypes().any { returnClass.isAssignableFrom(it) }
        }.getOrDefault(false)
    }

    private fun constructorInvokerDeclaredSupertypes(): List<Class<*>> {
        val classLoader = asmInfo.asmClass.classLoader ?: ClassLoader.getSystemClassLoader()
        return sequenceOf(classNode.superName)
            .plus(classNode.interfaces.asSequence())
            .filter { it != null && it != "java/lang/Object" }
            .map { it!!.replace('/', '.') }
            .mapNotNull { runCatching { Class.forName(it, false, classLoader) }.getOrNull() }
            .toList()
    }

    private fun ensureGeneratedMethodAbsent(
        methodName: String,
        methodDesc: String,
    ) {
        val existingMethod = classNode.methods.find { it.name == methodName && it.desc == methodDesc }
        if (existingMethod != null) {
            throw IllegalStateException("Generated method $methodName$methodDesc already exists in $className")
        }
    }

    /**
     * 应用 @AsmInject 注入
     *
     * 注意：此方法由 applyAsm() 按照特定顺序调用：
     * 1. 第一轮：RETURN 和 TAIL 注入
     * 2. 第二轮：其他注入（如 INVOKE）
     * 3. 第三轮：HEAD 注入（最后处理）
     *
     * 这个顺序确保了当 HEAD 注入取消方法时，RETURN 注入不会被执行。
     */
    private fun applyInject(
        method: Method,
        annotation: AsmInject,
        copyMethodNames: Map<String, String>,
    ): Boolean {
        val (targetMethod, methodSignature) = resolveAsmInjectTargetMethod(method, annotation, copyMethodNames)

        // 如果设置了 inline=true，则内联 ASM 方法的字节码
        val injectionCount =
            if (annotation.inline) {
                injectInlineCode(targetMethod, method, annotation, copyMethodNames)
            } else {
                // 否则使用普通的注入器在指定位置注入代码
                val injector = AsmInjectorFactory.createInjector(annotation.target, method, asmInfo)
                injector.injectCount(targetMethod)
            }

        return requireAsmInjectCount(
            injectionCount,
            annotation,
            method,
            methodSignature,
        )
    }

    private fun resolveAsmInjectTargetMethod(
        method: Method,
        annotation: AsmInject,
        copyMethodNames: Map<String, String>,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    hasCompatibleAsmInjectCandidate(method, annotation, copyMethodNames, candidate)
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(method.name))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@AsmInject handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun hasCompatibleAsmInjectCandidate(
        method: Method,
        annotation: AsmInject,
        copyMethodNames: Map<String, String>,
        targetMethod: MethodNode,
    ): Boolean =
        runCatching {
            val clone = cloneTargetMethod(targetMethod)
            if (annotation.inline) {
                injectInlineCode(clone, method, annotation, copyMethodNames) > 0
            } else {
                val injector = AsmInjectorFactory.createInjector(annotation.target, method, asmInfo)
                injector.injectCount(clone) > 0
            }
        }.getOrDefault(false)

    /**
     * 内联 ASM 方法的字节码到目标方法
     *
     * 注意：此方法也遵循 applyAsm() 的处理顺序：
     * - RETURN/TAIL 注入在 HEAD 注入之前处理
     * - 因此 RETURN 注入只会处理原始的 RETURN 指令，不会处理 HEAD 注入创建的 RETURN 指令
     */
    private fun injectInlineCode(
        target: MethodNode,
        asmMethod: Method,
        annotation: AsmInject,
        copyMethodNames: Map<String, String>,
    ): Int {
        val il =
            InlineCodeGenerator.inlineMethodCode(
                target,
                asmMethod,
                asmInfo,
                className,
                copyMethodNames,
            )

        // 根据注入点位置插入代码
        return when (annotation.target) {
            InjectionPoint.HEAD -> {
                // 在方法开头插入
                // 注意：HEAD 注入在最后处理，所以此时 RETURN 注入已经完成
                if (target.instructions.size() == 0) {
                    target.instructions.add(il)
                } else {
                    target.instructions.insertBefore(target.instructions.first, il)
                }
                1
            }
            InjectionPoint.TAIL -> {
                // 在所有 RETURN 之前插入
                // 注意：TAIL 注入在第一轮处理，只处理原始的 RETURN 指令
                val insns = target.instructions.toArray()
                var injectionCount = 0
                for (insn in insns) {
                    if (insn is InsnNode && insn.opcode in RETURN_OPS) {
                        target.instructions.insertBefore(insn, il)
                        injectionCount = 1
                        break
                    }
                }
                // 如果没有找到 RETURN，在末尾添加
                if (injectionCount == 0) {
                    target.instructions.add(il)
                    injectionCount = 1
                }
                injectionCount
            }
            InjectionPoint.RETURN -> {
                // 在每个 RETURN 之前插入
                // 注意：RETURN 注入在第一轮处理，只处理原始的 RETURN 指令
                // HEAD 注入创建的 RETURN 指令不会被处理（因为 HEAD 注入在 RETURN 注入之后执行）
                val insns = target.instructions.toArray()
                var injectionCount = 0
                for (insn in insns) {
                    if (insn is InsnNode && insn.opcode in RETURN_OPS) {
                        // 为每个 RETURN 创建新的指令列表副本
                        target.instructions.insertBefore(insn, cloneInsnList(il))
                        injectionCount++
                    }
                }
                injectionCount
            }
            else -> {
                // 默认在方法开头插入
                if (target.instructions.size() == 0) {
                    target.instructions.add(il)
                } else {
                    target.instructions.insertBefore(target.instructions.first, il)
                }
                1
            }
        }
    }

    private fun cloneInsnList(source: InsnList): InsnList {
        val labelMap = mutableMapOf<LabelNode, LabelNode>()
        source.toArray().filterIsInstance<LabelNode>().forEach { label ->
            labelMap[label] = LabelNode()
        }

        val cloned = InsnList()
        for (insn in source) {
            cloned.add(insn.clone(labelMap))
        }
        return cloned
    }

    companion object {
        private val RETURN_OPS =
            setOf(
                Opcodes.RETURN,
                Opcodes.IRETURN,
                Opcodes.LRETURN,
                Opcodes.FRETURN,
                Opcodes.DRETURN,
                Opcodes.ARETURN,
            )
        private val MODIFY_RECEIVER_FIELD_READ_OPS = setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)
        private val MODIFY_RECEIVER_FIELD_WRITE_OPS = setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC)
    }

    /**
     * 应用 @ModifyArg 修改参数
     */
    private fun applyModifyArg(
        method: Method,
        annotation: ModifyArg,
    ): Boolean {
        val (targetMethod, methodSignature) = resolveModifyArgTargetMethod(method, annotation)
        val injector = AsmInjectorFactory.createModifyArgInjector(
            method,
            asmInfo,
            annotation.index,
            annotation.at,
            annotation.ordinal,
            annotation.slice,
        )
        val injectionCount = injector.injectCount(targetMethod)
        if (annotation.require > 0 || annotation.allow >= 0 || annotation.expect != 1) {
            return requireModifyArgCount(
                injectionCount,
                annotation,
                method,
                methodSignature,
            )
        }
        return requireInjectorMatched(
            injectionCount > 0,
            "@ModifyArg",
            method,
            methodSignature,
        )
    }

    private fun resolveModifyArgTargetMethod(
        method: Method,
        annotation: ModifyArg,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    isModifyArgEntryTargetCompatible(method, annotation, candidate)
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(method.name))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@ModifyArg handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun isModifyArgEntryTargetCompatible(
        handlerMethod: Method,
        annotation: ModifyArg,
        targetMethod: MethodNode,
    ): Boolean =
        runCatching {
            require(annotation.at.value != InjectionPoint.INVOKE) {
                "@ModifyArg method inference currently supports only method-entry parameter mode"
            }

            val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)
            require(annotation.index >= 0 && annotation.index < targetParamTypes.size) {
                "Invalid @ModifyArg index ${annotation.index} for ${targetMethod.name}${targetMethod.desc}"
            }

            val targetParamType = targetParamTypes[annotation.index]
            validateModifyArgEntryHandlerSignature(handlerMethod, targetMethod, targetParamType)
        }.isSuccess

    private fun validateModifyArgEntryHandlerSignature(
        handlerMethod: Method,
        targetMethod: MethodNode,
        targetParamType: Type,
    ) {
        val handlerParamTypes = Type.getArgumentTypes(handlerMethod)
        if (handlerParamTypes.isEmpty() || !isModifyArgParameterCompatible(targetParamType, handlerParamTypes[0])) {
            throw IllegalArgumentException(
                "@ModifyArg handler ${handlerMethod.name} first parameter must be $targetParamType " +
                    "or compatible Object/Any, actual ${handlerParamTypes.toList()}",
            )
        }

        val handlerReturnType = Type.getReturnType(handlerMethod)
        if (!isModifyArgReturnCompatible(targetParamType, handlerReturnType, handlerMethod.returnType)) {
            throw IllegalArgumentException(
                "@ModifyArg handler ${handlerMethod.name} return type $handlerReturnType " +
                    "must match parameter type $targetParamType",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)
        val requestedTargetParamCount = handlerParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyArg handler ${handlerMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${targetMethod.name}${targetMethod.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = handlerParamTypes[index + 1]
            if (!isModifyArgParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyArg handler ${handlerMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }
    }

    private fun isModifyArgParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) {
            return true
        }
        if (!expected.isReferenceType() || !actual.isReferenceType()) {
            return false
        }
        if (actual.sort == Type.OBJECT &&
            (actual.internalName == "java/lang/Object" || actual.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val expectedClass = loadReferenceClass(expected)
            loadReferenceClass(actual).isAssignableFrom(expectedClass)
        }.getOrDefault(false)
    }

    private fun isModifyArgReturnCompatible(
        targetParamType: Type,
        handlerReturnType: Type,
        handlerReturnClass: Class<*>,
    ): Boolean {
        if (targetParamType == handlerReturnType) {
            return true
        }
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (!targetParamType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val targetParamClass = loadReferenceClass(targetParamType)
            targetParamClass.isAssignableFrom(handlerReturnClass)
        }.getOrDefault(false)
    }

    /**
     * 应用 @ModifyArgs 修改调用参数组。
     */
    private fun applyModifyArgs(
        method: Method,
        annotation: ModifyArgs,
    ): Boolean {
        val (targetMethod, methodSignature) = resolveModifyArgsTargetMethod(method, annotation)
        val injector = AsmInjectorFactory.createModifyArgsInjector(
            method,
            asmInfo,
            annotation.at,
            annotation.ordinal,
            annotation.slice,
        )
        val injectionCount = injector.injectCount(targetMethod)
        if (annotation.require > 0 || annotation.allow >= 0 || annotation.expect != 1) {
            return requireModifyArgsCount(
                injectionCount,
                annotation,
                method,
                methodSignature,
            )
        }
        return requireInjectorMatched(
            injectionCount > 0,
            "@ModifyArgs",
            method,
            methodSignature,
        )
    }

    private fun resolveModifyArgsTargetMethod(
        method: Method,
        annotation: ModifyArgs,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    isModifyArgsTargetCompatible(method, annotation, candidate)
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(method.name))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@ModifyArgs handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun isModifyArgsTargetCompatible(
        handlerMethod: Method,
        annotation: ModifyArgs,
        targetMethod: MethodNode,
    ): Boolean =
        runCatching {
            require(annotation.at.value == InjectionPoint.INVOKE) {
                "@ModifyArgs currently supports only INVOKE injection point"
            }
            validateModifyArgsHandlerSignature(handlerMethod, targetMethod)
            require(hasModifyArgsCallSite(targetMethod, annotation)) {
                "@ModifyArgs target ${targetMethod.name}${targetMethod.desc} has no matching call site"
            }
        }.isSuccess

    private fun validateModifyArgsHandlerSignature(
        handlerMethod: Method,
        targetMethod: MethodNode,
    ) {
        val handlerParamTypes = Type.getArgumentTypes(handlerMethod)
        val argsType = Type.getType(Args::class.java)
        if (handlerParamTypes.isEmpty() || handlerParamTypes[0] != argsType) {
            throw IllegalArgumentException(
                "@ModifyArgs handler ${handlerMethod.name} first parameter must be Args, " +
                    "actual ${handlerParamTypes.toList()}",
            )
        }

        val handlerReturnType = Type.getReturnType(handlerMethod)
        if (handlerReturnType != Type.VOID_TYPE) {
            throw IllegalArgumentException("@ModifyArgs handler ${handlerMethod.name} must return void, actual $handlerReturnType")
        }

        val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)
        val requestedTargetParamCount = handlerParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyArgs handler ${handlerMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${targetMethod.name}${targetMethod.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = handlerParamTypes[index + 1]
            if (!isModifyArgsParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyArgs handler ${handlerMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }
    }

    private fun hasModifyArgsCallSite(
        targetMethod: MethodNode,
        annotation: ModifyArgs,
    ): Boolean {
        val (targetOwner, targetName, targetDesc) = parseModifyArgsTargetMethod(annotation.at.target)
        if (targetName == null || targetDesc == null) {
            throw IllegalArgumentException("@ModifyArgs INVOKE requires at.target method signature")
        }

        val insns = targetMethod.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveModifyArgsSliceRange(insns, annotation.slice)
        var matchedOrdinal = 0
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (!matchesModifyArgsCallSite(insn, targetOwner, targetName, targetDesc)) {
                continue
            }
            val currentOrdinal = matchedOrdinal++
            if (annotation.ordinal < 0 || currentOrdinal == annotation.ordinal) {
                return true
            }
        }
        return false
    }

    private fun resolveModifyArgsSliceRange(
        insns: Array<AbstractInsnNode>,
        slice: Slice,
    ): Pair<Int, Int> {
        val startIndex =
            if (slice.from.target.isNotEmpty()) {
                val fromIndex = findModifyArgsSliceBoundaryIndex(insns, slice.from, 0) ?: return insns.size to insns.size
                fromIndex + 1
            } else {
                0
            }
        val endIndex =
            if (slice.to.target.isNotEmpty()) {
                findModifyArgsSliceBoundaryIndex(insns, slice.to, startIndex) ?: return insns.size to insns.size
            } else {
                insns.size
            }
        return startIndex to endIndex.coerceAtLeast(startIndex)
    }

    private fun findModifyArgsSliceBoundaryIndex(
        insns: Array<AbstractInsnNode>,
        at: At,
        startIndex: Int,
    ): Int? {
        require(at.value == InjectionPoint.INVOKE) {
            "Only INVOKE slice boundaries are supported for @ModifyArgs(INVOKE): ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseModifyArgsTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyArgs slice boundary method signature: ${at.target} " +
                    "(parsed: owner=$boundaryOwner, name=$boundaryName, desc=$boundaryDesc)",
            )
        }

        for (index in startIndex until insns.size) {
            val insn = insns[index]
            if (matchesModifyArgsCallSite(insn, boundaryOwner, boundaryName, boundaryDesc)) {
                return index
            }
        }

        return null
    }

    private fun parseModifyArgsTargetMethod(signature: String): Triple<String?, String?, String?> {
        if (signature.isEmpty()) {
            return Triple(null, null, null)
        }

        val parenIndex = signature.indexOf('(')
        if (parenIndex < 0) {
            return Triple(null, signature, null)
        }

        val ownerAndName = signature.substring(0, parenIndex)
        val desc = signature.substring(parenIndex)
        val slashIndex = ownerAndName.lastIndexOf('/')
        val dotIndex = ownerAndName.lastIndexOf('.')
        val separatorIndex = maxOf(slashIndex, dotIndex)

        return if (separatorIndex >= 0) {
            Triple(
                ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                ownerAndName.substring(separatorIndex + 1),
                desc,
            )
        } else {
            Triple(null, ownerAndName, desc)
        }
    }

    private fun matchesModifyArgsCallSite(
        insn: AbstractInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String?,
    ): Boolean =
        when (insn) {
            is MethodInsnNode -> matchesModifyArgsTargetMethod(insn, targetOwner, targetName, targetDesc)
            is InvokeDynamicInsnNode -> matchesModifyArgsInvokeDynamic(insn, targetOwner, targetName, targetDesc)
            else -> false
        }

    private fun matchesModifyArgsTargetMethod(
        insn: MethodInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String?,
    ): Boolean {
        if (targetOwner != null && insn.owner != targetOwner) {
            return false
        }
        if (insn.name != targetName) {
            return false
        }
        return targetDesc == null || insn.desc == targetDesc
    }

    private fun matchesModifyArgsInvokeDynamic(
        insn: InvokeDynamicInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String?,
    ): Boolean {
        if (targetOwner != null && insn.bsm.owner != targetOwner) {
            return false
        }
        if (insn.name != targetName && insn.bsm.name != targetName) {
            return false
        }
        return targetDesc == null || insn.desc == targetDesc
    }

    private fun isModifyArgsParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) {
            return true
        }
        if (!expected.isReferenceType() || !actual.isReferenceType()) {
            return false
        }
        if (actual.sort == Type.OBJECT &&
            (actual.internalName == "java/lang/Object" || actual.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val expectedClass = loadReferenceClass(expected)
            loadReferenceClass(actual).isAssignableFrom(expectedClass)
        }.getOrDefault(false)
    }

    /**
     * 应用 @ModifyReceiver 修改调用 receiver。
     */
    private fun applyModifyReceiver(
        method: Method,
        annotation: ModifyReceiver,
    ): Boolean {
        val (targetMethod, methodSignature) = resolveModifyReceiverTargetMethod(method, annotation)
        val injector = AsmInjectorFactory.createModifyReceiverInjector(
            method,
            asmInfo,
            annotation.at,
            annotation.ordinal,
            annotation.slice,
        )
        val injectionCount = injector.injectCount(targetMethod)
        if (annotation.require > 0 || annotation.allow >= 0 || annotation.expect != 1) {
            return requireModifyReceiverCount(
                injectionCount,
                annotation,
                method,
                methodSignature,
            )
        }
        return requireInjectorMatched(
            injectionCount > 0,
            "@ModifyReceiver",
            method,
            methodSignature,
        )
    }

    private fun resolveModifyReceiverTargetMethod(
        method: Method,
        annotation: ModifyReceiver,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    isModifyReceiverTargetCompatible(method, annotation, candidate)
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(method.name))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@ModifyReceiver handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun isModifyReceiverTargetCompatible(
        handlerMethod: Method,
        annotation: ModifyReceiver,
        targetMethod: MethodNode,
    ): Boolean =
        runCatching {
            val receiverType = resolveModifyReceiverType(targetMethod, annotation)
            validateModifyReceiverHandlerSignature(handlerMethod, targetMethod, receiverType)
        }.isSuccess

    private fun resolveModifyReceiverType(
        targetMethod: MethodNode,
        annotation: ModifyReceiver,
    ): Type {
        val receiverTypes =
            when (annotation.at.value) {
                InjectionPoint.INVOKE -> collectModifyReceiverInvokeTypes(targetMethod, annotation)
                InjectionPoint.FIELD -> collectModifyReceiverFieldTypes(targetMethod, annotation, fieldAssign = false)
                InjectionPoint.FIELD_ASSIGN -> collectModifyReceiverFieldTypes(targetMethod, annotation, fieldAssign = true)
                else -> throw IllegalArgumentException(
                    "@ModifyReceiver supports only INVOKE, FIELD and FIELD_ASSIGN injection points",
                )
            }
        return receiverTypes.singleOrNull()
            ?: throw IllegalArgumentException(
                "@ModifyReceiver cannot resolve unique receiver type in ${targetMethod.name}${targetMethod.desc}: $receiverTypes",
            )
    }

    private fun collectModifyReceiverInvokeTypes(
        targetMethod: MethodNode,
        annotation: ModifyReceiver,
    ): List<Type> {
        val (targetOwner, targetName, targetDesc) = parseModifyReceiverTargetMethod(annotation.at.target)
        if (targetName == null || targetDesc == null) {
            throw IllegalArgumentException("@ModifyReceiver INVOKE requires at.target method signature")
        }

        val insns = targetMethod.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveModifyReceiverSliceRange(insns, annotation.slice)
        val receiverTypes = mutableListOf<Type>()
        var matchedOrdinal = 0
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is MethodInsnNode || !matchesModifyReceiverTargetMethod(insn, targetOwner, targetName, targetDesc)) {
                continue
            }
            val currentOrdinal = matchedOrdinal++
            if (annotation.ordinal >= 0 && currentOrdinal != annotation.ordinal) {
                continue
            }
            if (insn.opcode == Opcodes.INVOKESTATIC || insn.name == "<init>") {
                throw IllegalArgumentException(
                    "@ModifyReceiver supports only instance method calls, target ${insn.owner}.${insn.name}${insn.desc}",
                )
            }
            receiverTypes.add(Type.getObjectType(insn.owner))
        }
        return receiverTypes
    }

    private fun collectModifyReceiverFieldTypes(
        targetMethod: MethodNode,
        annotation: ModifyReceiver,
        fieldAssign: Boolean,
    ): List<Type> {
        val fieldTarget = parseModifyReceiverFieldTarget(annotation.at.target)
        if (fieldTarget.name == null) {
            val mode = if (fieldAssign) "FIELD_ASSIGN" else "FIELD"
            throw IllegalArgumentException("@ModifyReceiver $mode requires at.target field signature")
        }

        val insns = targetMethod.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveModifyReceiverSliceRange(insns, annotation.slice)
        val receiverTypes = mutableListOf<Type>()
        var matchedOrdinal = 0
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            val expectedOpcodes =
                if (fieldAssign) {
                    MODIFY_RECEIVER_FIELD_WRITE_OPS
                } else {
                    MODIFY_RECEIVER_FIELD_READ_OPS
                }
            if (insn !is FieldInsnNode || insn.opcode !in expectedOpcodes || !matchesModifyReceiverTargetField(insn, fieldTarget)) {
                continue
            }
            val currentOrdinal = matchedOrdinal++
            if (annotation.ordinal >= 0 && currentOrdinal != annotation.ordinal) {
                continue
            }
            if (insn.opcode == Opcodes.GETSTATIC || insn.opcode == Opcodes.PUTSTATIC) {
                val action = if (fieldAssign) "writes" else "reads"
                throw IllegalArgumentException(
                    "@ModifyReceiver supports only instance field $action, target ${insn.owner}.${insn.name}:${insn.desc}",
                )
            }
            receiverTypes.add(Type.getObjectType(insn.owner))
        }
        return receiverTypes
    }

    private fun validateModifyReceiverHandlerSignature(
        handlerMethod: Method,
        targetMethod: MethodNode,
        receiverType: Type,
    ) {
        val handlerParamTypes = Type.getArgumentTypes(handlerMethod)
        if (handlerParamTypes.isEmpty() || !isModifyReceiverParameterCompatible(receiverType, handlerParamTypes[0])) {
            throw IllegalArgumentException(
                "@ModifyReceiver handler ${handlerMethod.name} first parameter must be $receiverType, " +
                    "actual ${handlerParamTypes.toList()}",
            )
        }

        val handlerReturnType = Type.getReturnType(handlerMethod)
        if (!isModifyReceiverReturnCompatible(receiverType, handlerReturnType, handlerMethod.returnType)) {
            throw IllegalArgumentException(
                "@ModifyReceiver handler ${handlerMethod.name} return type $handlerReturnType " +
                    "must be compatible with receiver type $receiverType",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)
        val requestedTargetParamCount = handlerParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyReceiver handler ${handlerMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${targetMethod.name}${targetMethod.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = handlerParamTypes[index + 1]
            if (!isModifyReceiverParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyReceiver handler ${handlerMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }
    }

    private fun resolveModifyReceiverSliceRange(
        insns: Array<AbstractInsnNode>,
        slice: Slice,
    ): Pair<Int, Int> {
        val startIndex =
            if (slice.from.target.isNotEmpty()) {
                val fromIndex = findModifyReceiverSliceBoundaryIndex(insns, slice.from, 0) ?: return insns.size to insns.size
                fromIndex + 1
            } else {
                0
            }
        val endIndex =
            if (slice.to.target.isNotEmpty()) {
                findModifyReceiverSliceBoundaryIndex(insns, slice.to, startIndex) ?: return insns.size to insns.size
            } else {
                insns.size
            }
        return startIndex to endIndex.coerceAtLeast(startIndex)
    }

    private fun findModifyReceiverSliceBoundaryIndex(
        insns: Array<AbstractInsnNode>,
        at: At,
        startIndex: Int,
    ): Int? {
        require(at.value == InjectionPoint.INVOKE) {
            "Only INVOKE slice boundaries are supported for @ModifyReceiver: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseModifyReceiverTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyReceiver slice boundary method signature: ${at.target} " +
                    "(parsed: owner=$boundaryOwner, name=$boundaryName, desc=$boundaryDesc)",
            )
        }

        for (index in startIndex until insns.size) {
            val insn = insns[index]
            if (insn is MethodInsnNode && matchesModifyReceiverTargetMethod(insn, boundaryOwner, boundaryName, boundaryDesc)) {
                return index
            }
        }

        return null
    }

    private fun parseModifyReceiverTargetMethod(signature: String): Triple<String?, String?, String?> {
        if (signature.isEmpty()) {
            return Triple(null, null, null)
        }

        val parenIndex = signature.indexOf('(')
        if (parenIndex < 0) {
            return Triple(null, signature, null)
        }

        val ownerAndName = signature.substring(0, parenIndex)
        val desc = signature.substring(parenIndex)
        val slashIndex = ownerAndName.lastIndexOf('/')
        val dotIndex = ownerAndName.lastIndexOf('.')
        val separatorIndex = maxOf(slashIndex, dotIndex)

        return if (separatorIndex >= 0) {
            Triple(
                ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                ownerAndName.substring(separatorIndex + 1),
                desc,
            )
        } else {
            Triple(null, ownerAndName, desc)
        }
    }

    private fun parseModifyReceiverFieldTarget(signature: String): ModifyReceiverFieldTarget {
        if (signature.isEmpty()) {
            return ModifyReceiverFieldTarget(null, null, null)
        }

        val ownerAndName: String
        val desc: String?
        val colonIndex = signature.indexOf(':')
        if (colonIndex >= 0) {
            ownerAndName = signature.substring(0, colonIndex)
            desc = signature.substring(colonIndex + 1)
        } else {
            ownerAndName = signature
            desc = null
        }

        val slashIndex = ownerAndName.lastIndexOf('/')
        val dotIndex = ownerAndName.lastIndexOf('.')
        val separatorIndex = maxOf(slashIndex, dotIndex)
        return if (separatorIndex >= 0) {
            ModifyReceiverFieldTarget(
                ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                ownerAndName.substring(separatorIndex + 1),
                desc,
            )
        } else {
            ModifyReceiverFieldTarget(null, ownerAndName, desc)
        }
    }

    private fun matchesModifyReceiverTargetMethod(
        insn: MethodInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String?,
    ): Boolean {
        if (targetOwner != null && insn.owner != targetOwner) {
            return false
        }
        if (insn.name != targetName) {
            return false
        }
        return targetDesc == null || insn.desc == targetDesc
    }

    private fun matchesModifyReceiverTargetField(
        insn: FieldInsnNode,
        target: ModifyReceiverFieldTarget,
    ): Boolean {
        if (target.owner != null && insn.owner != target.owner) {
            return false
        }
        if (target.name != null && insn.name != target.name) {
            return false
        }
        return target.desc == null || insn.desc == target.desc
    }

    private fun isModifyReceiverParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) {
            return true
        }
        if (!expected.isReferenceType() || !actual.isReferenceType()) {
            return false
        }
        if (actual.sort == Type.OBJECT &&
            (actual.internalName == "java/lang/Object" || actual.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val expectedClass = loadReferenceClass(expected)
            loadReferenceClass(actual).isAssignableFrom(expectedClass)
        }.getOrDefault(false)
    }

    private fun isModifyReceiverReturnCompatible(
        receiverType: Type,
        handlerReturnType: Type,
        handlerReturnClass: Class<*>,
    ): Boolean {
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (receiverType == handlerReturnType) {
            return true
        }
        if (!receiverType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val receiverClass = loadReferenceClass(receiverType)
            receiverClass.isAssignableFrom(handlerReturnClass)
        }.getOrDefault(false)
    }

    private data class ModifyReceiverFieldTarget(
        val owner: String?,
        val name: String?,
        val desc: String?,
    )

    /**
     * 应用 @WrapOperation 包裹原始操作。
     */
    private fun applyWrapOperation(
        method: Method,
        annotation: WrapOperation,
    ): Boolean {
        val (targetMethod, methodSignature) = resolveWrapOperationTargetMethod(method, annotation)
        val injector = AsmInjectorFactory.createWrapOperationInjector(
            method,
            asmInfo,
            annotation.at,
            annotation.ordinal,
            annotation.slice,
        )
        val injectionCount = injector.injectCount(targetMethod)
        if (annotation.require > 0 || annotation.allow >= 0 || annotation.expect != 1) {
            return requireWrapOperationCount(
                injectionCount,
                annotation,
                method,
                methodSignature,
            )
        }
        return requireInjectorMatched(
            injectionCount > 0,
            "@WrapOperation",
            method,
            methodSignature,
        )
    }

    private fun resolveWrapOperationTargetMethod(
        method: Method,
        annotation: WrapOperation,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    hasCompatibleWrapOperationCandidate(method, annotation, candidate)
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(method.name))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@WrapOperation handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun hasCompatibleWrapOperationCandidate(
        method: Method,
        annotation: WrapOperation,
        targetMethod: MethodNode,
    ): Boolean =
        runCatching {
            val injector = AsmInjectorFactory.createWrapOperationInjector(
                method,
                asmInfo,
                annotation.at,
                annotation.ordinal,
                annotation.slice,
            )
            injector.injectCount(cloneTargetMethod(targetMethod)) > 0
        }.getOrDefault(false)

    /**
     * 应用 @WrapWithCondition 条件包裹调用。
     */
    private fun applyWrapWithCondition(
        method: Method,
        annotation: WrapWithCondition,
    ): Boolean {
        val (targetMethod, methodSignature) = resolveWrapWithConditionTargetMethod(method, annotation)
        val injector = AsmInjectorFactory.createWrapWithConditionInjector(
            method,
            asmInfo,
            annotation.at,
            annotation.ordinal,
            annotation.slice,
        )
        val injectionCount = injector.injectCount(targetMethod)
        if (annotation.require > 0 || annotation.allow >= 0 || annotation.expect != 1) {
            return requireWrapWithConditionCount(
                injectionCount,
                annotation,
                method,
                methodSignature,
            )
        }
        return requireInjectorMatched(
            injectionCount > 0,
            "@WrapWithCondition",
            method,
            methodSignature,
        )
    }

    private fun resolveWrapWithConditionTargetMethod(
        method: Method,
        annotation: WrapWithCondition,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    hasCompatibleWrapWithConditionCandidate(method, annotation, candidate)
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(method.name))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@WrapWithCondition handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun hasCompatibleWrapWithConditionCandidate(
        method: Method,
        annotation: WrapWithCondition,
        targetMethod: MethodNode,
    ): Boolean =
        runCatching {
            val injector = AsmInjectorFactory.createWrapWithConditionInjector(
                method,
                asmInfo,
                annotation.at,
                annotation.ordinal,
                annotation.slice,
            )
            injector.injectCount(cloneTargetMethod(targetMethod)) > 0
        }.getOrDefault(false)

    /**
     * 应用 @ModifyExpressionValue 修改表达式值。
     */
    private fun applyModifyExpressionValue(
        method: Method,
        annotation: ModifyExpressionValue,
    ): Boolean {
        val (targetMethod, methodSignature) = resolveModifyExpressionValueTargetMethod(method, annotation)
        val injector = AsmInjectorFactory.createModifyExpressionValueInjector(
            method,
            asmInfo,
            annotation.at,
            annotation.ordinal,
            annotation.slice,
        )
        val injectionCount = injector.injectCount(targetMethod)
        if (annotation.require > 0 || annotation.allow >= 0 || annotation.expect != 1) {
            return requireModifyExpressionValueCount(
                injectionCount,
                annotation,
                method,
                methodSignature,
            )
        }
        return requireInjectorMatched(
            injectionCount > 0,
            "@ModifyExpressionValue",
            method,
            methodSignature,
        )
    }

    private fun resolveModifyExpressionValueTargetMethod(
        method: Method,
        annotation: ModifyExpressionValue,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    hasCompatibleModifyExpressionValueCandidate(method, annotation, candidate)
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(method.name))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@ModifyExpressionValue handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun hasCompatibleModifyExpressionValueCandidate(
        method: Method,
        annotation: ModifyExpressionValue,
        targetMethod: MethodNode,
    ): Boolean =
        runCatching {
            val injector = AsmInjectorFactory.createModifyExpressionValueInjector(
                method,
                asmInfo,
                annotation.at,
                annotation.ordinal,
                annotation.slice,
            )
            injector.injectCount(cloneTargetMethod(targetMethod)) > 0
        }.getOrDefault(false)

    private fun cloneTargetMethod(method: MethodNode): MethodNode {
        val clone = MethodNode(
            method.access,
            method.name,
            method.desc,
            method.signature,
            method.exceptions?.toTypedArray(),
        )
        method.accept(clone)
        return clone
    }

    /**
     * 应用 @ModifyVariable 修改局部变量。
     */
    private fun applyModifyVariable(
        method: Method,
        annotation: ModifyVariable,
    ): Boolean {
        val (targetMethod, methodSignature) = resolveModifyVariableTargetMethod(method, annotation)
        val injector =
            AsmInjectorFactory.createModifyVariableInjector(
                method,
                asmInfo,
                annotation.at.value,
                annotation.index,
                annotation.ordinal,
                annotation.slice,
            )
        val injectionCount = injector.injectCount(targetMethod)
        if (annotation.require > 0 || annotation.allow >= 0 || annotation.expect != 1) {
            return requireModifyVariableCount(
                injectionCount,
                annotation,
                method,
                methodSignature,
            )
        }
        return requireInjectorMatched(
            injectionCount > 0,
            "@ModifyVariable",
            method,
            methodSignature,
        )
    }

    private fun resolveModifyVariableTargetMethod(
        method: Method,
        annotation: ModifyVariable,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    isModifyVariableTargetCompatible(method, annotation, candidate)
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(method.name))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@ModifyVariable handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun isModifyVariableTargetCompatible(
        handlerMethod: Method,
        annotation: ModifyVariable,
        targetMethod: MethodNode,
    ): Boolean =
        runCatching {
            val variableType = resolveModifyVariableType(handlerMethod, annotation, targetMethod)
            validateModifyVariableHandlerSignature(handlerMethod, targetMethod, variableType)
        }.isSuccess

    private fun resolveModifyVariableType(
        handlerMethod: Method,
        annotation: ModifyVariable,
        targetMethod: MethodNode,
    ): Type {
        val handlerParams = Type.getArgumentTypes(handlerMethod)
        if (handlerParams.isEmpty()) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${handlerMethod.name} must take at least one argument for the original variable value",
            )
        }

        return when (annotation.at.value) {
            InjectionPoint.HEAD -> resolveModifyVariableHeadType(annotation, targetMethod, handlerParams[0])
            InjectionPoint.LOAD,
            InjectionPoint.STORE,
            -> handlerParams[0]
            else -> throw IllegalArgumentException(
                "@ModifyVariable currently supports only HEAD, LOAD and STORE injection points",
            )
        }
    }

    private fun resolveModifyVariableHeadType(
        annotation: ModifyVariable,
        targetMethod: MethodNode,
        handlerVariableType: Type,
    ): Type {
        val variables = collectModifyVariableHeadParameters(targetMethod)
        val variable =
            if (annotation.index >= 0) {
                variables.find { it.index == annotation.index }
            } else {
                val matchingVariables = variables.filter { it.type == handlerVariableType }
                if (annotation.ordinal < 0) {
                    matchingVariables.singleOrNull()
                } else {
                    matchingVariables.getOrNull(annotation.ordinal)
                }
            }
        return variable?.type
            ?: throw IllegalArgumentException(
                "@ModifyVariable cannot resolve HEAD variable: index=${annotation.index}, " +
                    "ordinal=${annotation.ordinal}, type=$handlerVariableType",
            )
    }

    private fun collectModifyVariableHeadParameters(targetMethod: MethodNode): List<ModifyVariableHeadVariable> {
        val isStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0
        var slot = if (isStatic) 0 else 1
        return buildList {
            for (argumentType in Type.getArgumentTypes(targetMethod.desc)) {
                add(ModifyVariableHeadVariable(slot, argumentType))
                slot += argumentType.size
            }
        }
    }

    private data class ModifyVariableHeadVariable(
        val index: Int,
        val type: Type,
    )

    private fun validateModifyVariableHandlerSignature(
        handlerMethod: Method,
        targetMethod: MethodNode,
        variableType: Type,
    ) {
        val handlerParams = Type.getArgumentTypes(handlerMethod)
        if (handlerParams.isEmpty() || !isModifyVariableParameterCompatible(variableType, handlerParams[0])) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${handlerMethod.name} first parameter must be $variableType " +
                    "or compatible Object/Any, actual ${handlerParams.toList()}",
            )
        }

        val handlerReturnType = Type.getReturnType(handlerMethod)
        if (!isModifyVariableReturnCompatible(variableType, handlerReturnType, handlerMethod.returnType)) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${handlerMethod.name} return type $handlerReturnType " +
                    "must match variable type $variableType",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)
        val requestedTargetParamCount = handlerParams.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${handlerMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${targetMethod.name}${targetMethod.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = handlerParams[index + 1]
            if (!isModifyVariableParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyVariable handler ${handlerMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }
    }

    private fun isModifyVariableParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) {
            return true
        }
        if (!expected.isReferenceType() || !actual.isReferenceType()) {
            return false
        }
        if (actual.sort == Type.OBJECT &&
            (actual.internalName == "java/lang/Object" || actual.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val expectedClass = loadReferenceClass(expected)
            loadReferenceClass(actual).isAssignableFrom(expectedClass)
        }.getOrDefault(false)
    }

    private fun isModifyVariableReturnCompatible(
        variableType: Type,
        handlerReturnType: Type,
        handlerReturnClass: Class<*>,
    ): Boolean {
        if (variableType == handlerReturnType) {
            return true
        }
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (!variableType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val variableClass = loadReferenceClass(variableType)
            variableClass.isAssignableFrom(handlerReturnClass)
        }.getOrDefault(false)
    }

    /**
     * 应用 @Redirect 重定向
     */
    private fun applyRedirect(
        method: Method,
        annotation: Redirect,
    ): Boolean {
        // 组合 target 和 at.target 来构建完整的方法签名
        val redirectTarget = buildRedirectTarget(annotation.target, annotation.at.target)
        val (targetMethod, methodSignature) = resolveRedirectTargetMethod(method, annotation, redirectTarget)

        val injector =
            AsmInjectorFactory.createRedirectInjector(
                method,
                asmInfo,
                redirectTarget,
                annotation.at.value,
                annotation.ordinal,
                annotation.slice,
                annotation.at.args,
            )
        val injectionCount = injector.injectCount(targetMethod)
        if (annotation.require > 0 || annotation.allow >= 0 || annotation.expect != 1) {
            return requireRedirectCount(
                injectionCount,
                annotation,
                method,
                methodSignature,
            )
        }
        return requireInjectorMatched(
            injectionCount > 0,
            "@Redirect",
            method,
            methodSignature,
        )
    }

    private fun resolveRedirectTargetMethod(
        method: Method,
        annotation: Redirect,
        redirectTarget: String,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    hasCompatibleRedirectCandidate(method, annotation, redirectTarget, candidate)
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(method.name))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@Redirect handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun hasCompatibleRedirectCandidate(
        method: Method,
        annotation: Redirect,
        redirectTarget: String,
        targetMethod: MethodNode,
    ): Boolean =
        runCatching {
            val injector =
                AsmInjectorFactory.createRedirectInjector(
                    method,
                    asmInfo,
                    redirectTarget,
                    annotation.at.value,
                    annotation.ordinal,
                    annotation.slice,
                    annotation.at.args,
                )
            injector.injectCount(cloneTargetMethod(targetMethod)) > 0
        }.getOrDefault(false)

    /**
     * 构建 Redirect 目标方法签名
     * 如果 at.target 存在，则组合为 "owner/method(desc)"
     * 否则直接使用 target
     */
    private fun buildRedirectTarget(
        target: String,
        atTarget: String,
    ): String {
        // 如果 target 为空，直接使用 atTarget
        if (target.isEmpty()) {
            return atTarget
        }
        
        if (atTarget.isEmpty()) {
            return target
        }

        // 如果 target 包含方法名和描述符,组合 owner
        // 格式: "methodName(desc)" 或 "owner.methodName(desc)"
        if (target.contains("(")) {
            val parenIndex = target.indexOf('(')
            val methodPart = target.substring(0, parenIndex)
            val descPart = target.substring(parenIndex)

            // 如果 methodPart 不包含点,说明没有 owner,需要添加
            if (!methodPart.contains(".") && !methodPart.contains("/")) {
                return "$atTarget.$methodPart$descPart"
            }
        }

        return target
    }

    /**
     * 应用 @Overwrite 覆盖
     * 注意：@Overwrite 可以覆写已经被 @ReplaceAllMethods 替换的方法
     * 多个 @Overwrite 可以依次覆写同一个方法，后面的会覆盖前面的
     */
    private fun applyOverwrite(
        method: Method,
        annotation: Overwrite,
        copyMethodNames: Map<String, String>,
    ): Boolean {
        val methodSignature =
            if (annotation.method.isEmpty()) {
                buildMethodSignature(method)
            } else {
                annotation.method
            }
        val targetMethod = findTargetMethod(methodSignature)
        if (targetMethod == null) {
            throw IllegalStateException(buildMissingTargetMethodMessage(methodSignature))
        }

        // 覆写方法：清空原方法体并替换为 asm 方法的内容
        // 这允许后续的 @Overwrite 再次覆写这个方法
        val injector = AsmInjectorFactory.createOverwriteInjector(method, asmInfo, copyMethodNames)
        return injector.inject(targetMethod)
    }

    /**
     * 应用 @Copy 复制
     * 将 ASM 方法复制到目标类中作为新方法
     * 如果目标方法已存在，会创建失败
     */
    private fun applyCopy(
        method: Method,
        annotation: Copy,
        copyMethodNames: Map<String, String>,
    ): Boolean {
        // 确定目标方法签名
        val methodSignature =
            if (annotation.method.isEmpty()) {
                // 如果注解中没有指定方法，则使用 ASM 方法名和描述符
                buildMethodSignature(method)
            } else {
                annotation.method
            }

        val (methodName, methodDesc) = parseMethodSignature(methodSignature)
        val copiedMethodName = copyMethodNames[copyMethodKey(method)] ?: methodName

        // 检查目标方法是否已存在
        val existingMethod = classNode.methods.find { it.name == methodName && it.desc == methodDesc }
        if (existingMethod != null && copiedMethodName == methodName) {
            System.err.println(
                "Warning: Method $methodSignature already exists in class $className. " +
                    "Cannot copy method from ${asmInfo.asmClass.name}. Use @Overwrite to replace it.",
            )
            return false
        }

        // 创建目标方法节点（使用目标方法的签名）
        val targetMethod =
            MethodNode(
                copyMethodAccess(method, copiedMethodName != methodName),
                copiedMethodName,
                methodDesc,
                null,
                null,
            )

        // 使用 CopyInjector 创建新方法
        val injector = AsmInjectorFactory.createCopyInjector(method, asmInfo, copyMethodNames)
        val newMethod = injector.createMethod(targetMethod)

        // 将新方法添加到目标类
        classNode.methods.add(newMethod)

        return true
    }

    private fun buildCopyMethodNames(methods: List<Method>): Map<String, String> {
        val copyMethodNames = mutableMapOf<String, String>()
        val reservedMethodSignatures = classNode.methods.mapTo(mutableSetOf()) { "${it.name}${it.desc}" }

        for (method in methods) {
            val copyAnnotation = method.getAnnotation(Copy::class.java) ?: continue
            val methodSignature =
                if (copyAnnotation.method.isEmpty()) {
                    buildMethodSignature(method)
                } else {
                    copyAnnotation.method
                }
            val (methodName, methodDesc) = parseMethodSignature(methodSignature)
            val copiedMethodName =
                if (method.isAnnotationPresent(Unique::class.java) &&
                    reservedMethodSignatures.contains("$methodName$methodDesc")
                ) {
                    nextUniqueCopyMethodName(methodName, methodDesc, reservedMethodSignatures)
                } else {
                    methodName
                }

            copyMethodNames[copyMethodKey(method)] = copiedMethodName
            reservedMethodSignatures.add("$copiedMethodName$methodDesc")
        }

        return copyMethodNames
    }

    private fun nextUniqueCopyMethodName(
        methodName: String,
        methodDesc: String,
        reservedMethodSignatures: Set<String>,
    ): String {
        val baseName = "$methodName\$asm\$unique"
        var candidate = baseName
        var index = 0
        while (reservedMethodSignatures.contains("$candidate$methodDesc")) {
            index++
            candidate = "$baseName\$$index"
        }
        return candidate
    }

    private fun copyMethodKey(method: Method): String =
        "${method.name}${Type.getMethodDescriptor(method)}"

    private fun copyMethodAccess(
        method: Method,
        unique: Boolean,
    ): Int {
        if (!unique) {
            return Opcodes.ACC_PUBLIC
        }

        var access = Opcodes.ACC_PRIVATE or Opcodes.ACC_SYNTHETIC
        if (Modifier.isStatic(method.modifiers)) {
            access = access or Opcodes.ACC_STATIC
        }
        return access
    }

    /**
     * 应用 @WrapMethod 包裹整个目标方法。
     */
    private fun applyWrapMethod(
        method: Method,
        annotation: WrapMethod,
    ): Boolean {
        val (targetMethod, methodSignature) = resolveWrapMethodTargetMethod(method, annotation)
        wrapMethod(method, targetMethod)
        return requireWrapMethodCount(1, annotation, method, methodSignature)
    }

    private fun resolveWrapMethodTargetMethod(
        method: Method,
        annotation: WrapMethod,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val inferredSignature = buildWrapMethodSignature(method)
        findTargetMethod(inferredSignature)?.let { targetMethod ->
            return targetMethod to inferredSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    runCatching {
                        validateWrapMethodHandlerSignature(method, candidate)
                    }.isSuccess
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(inferredSignature))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@WrapMethod handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun wrapMethod(
        handlerMethod: Method,
        targetMethod: MethodNode,
    ) {
        if (targetMethod.name == "<init>" || targetMethod.name == "<clinit>") {
            throw IllegalStateException("@WrapMethod does not support constructor or class initializer targets")
        }
        if ((targetMethod.access and Opcodes.ACC_ABSTRACT) != 0 || (targetMethod.access and Opcodes.ACC_NATIVE) != 0) {
            throw IllegalStateException("@WrapMethod target ${targetMethod.name}${targetMethod.desc} must have a method body")
        }

        validateWrapMethodHandlerSignature(handlerMethod, targetMethod)

        val originalName = targetMethod.name
        val originalAccess = targetMethod.access
        val originalSignature = targetMethod.signature
        val originalExceptions = targetMethod.exceptions?.toTypedArray()
        val wrappedOriginalName = nextWrappedOriginalMethodName(originalName, targetMethod.desc)

        targetMethod.name = wrappedOriginalName
        targetMethod.access = toWrappedOriginalAccess(originalAccess)

        classNode.methods.add(
            buildWrapMethodWrapper(
                handlerMethod,
                originalName,
                originalAccess,
                originalSignature,
                originalExceptions,
                targetMethod.desc,
                wrappedOriginalName,
            ),
        )
    }

    private fun buildWrapMethodWrapper(
        handlerMethod: Method,
        originalName: String,
        originalAccess: Int,
        originalSignature: String?,
        originalExceptions: Array<String>?,
        targetDesc: String,
        wrappedOriginalName: String,
    ): MethodNode {
        val wrapper =
            MethodNode(
                originalAccess and Opcodes.ACC_ABSTRACT.inv() and Opcodes.ACC_NATIVE.inv(),
                originalName,
                targetDesc,
                originalSignature,
                originalExceptions,
            )
        val isStaticTarget = (originalAccess and Opcodes.ACC_STATIC) != 0
        val targetParamTypes = Type.getArgumentTypes(targetDesc)
        val returnType = Type.getReturnType(targetDesc)
        val handlerReturnType = Type.getReturnType(handlerMethod)
        val il = InsnList()

        addWrapMethodHandlerOwner(il, handlerMethod)
        loadWrapMethodParameters(il, targetDesc, isStaticTarget)
        createWrapMethodOperation(il, wrappedOriginalName, targetDesc, isStaticTarget, targetParamTypes)
        il.add(
            MethodInsnNode(
                wrapMethodHandlerOpcode(handlerMethod),
                Type.getType(asmInfo.asmClass).internalName,
                handlerMethod.name,
                Type.getMethodDescriptor(handlerMethod),
                false,
            ),
        )
        if (returnType != Type.VOID_TYPE && returnType != handlerReturnType && returnType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, returnType.internalName))
        }
        il.add(InstructionUtil.makeReturn(returnType))

        wrapper.instructions = il
        wrapper.maxLocals = methodParameterLocalCount(targetDesc, isStaticTarget)
        wrapper.maxStack = 0
        return wrapper
    }

    private fun buildWrapMethodSignature(method: Method): String {
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(method)
        val operationIndex = actualParams.indexOfFirst { it == operationType }
        if (operationIndex < 0) {
            throw IllegalStateException("@WrapMethod handler ${method.name} must declare Operation parameter")
        }
        val targetParamTypes = actualParams.take(operationIndex).toTypedArray()
        return method.name + Type.getMethodDescriptor(Type.getReturnType(method), *targetParamTypes)
    }

    private fun validateWrapMethodHandlerSignature(
        handlerMethod: Method,
        targetMethod: MethodNode,
    ) {
        val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)
        val actualParams = Type.getArgumentTypes(handlerMethod)
        val operationType = Type.getType(Operation::class.java)
        val operationIndex = targetParamTypes.size

        if (actualParams.size != operationIndex + 1 || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapMethod handler ${handlerMethod.name} must declare target parameters followed by Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        targetParamTypes.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isWrapMethodParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapMethod handler ${handlerMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetReturnType = Type.getReturnType(targetMethod.desc)
        val handlerReturnType = Type.getReturnType(handlerMethod)
        if (!isWrapMethodReturnCompatible(targetReturnType, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapMethod handler ${handlerMethod.name} return type mismatch: " +
                    "original $targetReturnType, handler $handlerReturnType",
            )
        }
    }

    private fun createWrapMethodOperation(
        il: InsnList,
        methodName: String,
        methodDesc: String,
        isStaticTarget: Boolean,
        targetParamTypes: Array<Type>,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(Type.getObjectType(className)))
        il.add(LdcInsnNode(methodName))
        il.add(LdcInsnNode(methodDesc))
        if (isStaticTarget) {
            il.add(InsnNode(Opcodes.ICONST_1))
        }
        il.add(LdcInsnNode(targetParamTypes.size))
        il.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"))
        for (index in targetParamTypes.indices) {
            il.add(InsnNode(Opcodes.DUP))
            il.add(LdcInsnNode(index))
            il.add(InstructionUtil.loadType(targetParamTypes[index]))
            il.add(InsnNode(Opcodes.AASTORE))
        }
        if (isStaticTarget) {
            il.add(
                MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    operationType.internalName,
                    "<init>",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Z[Ljava/lang/Class;)V",
                    false,
                ),
            )
        } else {
            il.add(VarInsnNode(Opcodes.ALOAD, 0))
            il.add(
                MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    operationType.internalName,
                    "<init>",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Class;Ljava/lang/Object;)V",
                    false,
                ),
            )
        }
    }

    private fun addWrapMethodHandlerOwner(
        il: InsnList,
        handlerMethod: Method,
    ) {
        if (Modifier.isStatic(handlerMethod.modifiers)) {
            return
        }

        val ownerType = Type.getType(asmInfo.asmClass)
        if (isAsmKotlinObject()) {
            il.add(
                FieldInsnNode(
                    Opcodes.GETSTATIC,
                    ownerType.internalName,
                    "INSTANCE",
                    "L${ownerType.internalName};",
                ),
            )
            return
        }

        il.add(TypeInsnNode(Opcodes.NEW, ownerType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, ownerType.internalName, "<init>", "()V", false))
    }

    private fun loadWrapMethodParameters(
        il: InsnList,
        targetDesc: String,
        isStaticTarget: Boolean,
    ) {
        var varIndex = if (isStaticTarget) 0 else 1
        for (paramType in Type.getArgumentTypes(targetDesc)) {
            il.add(InstructionUtil.loadParam(paramType, varIndex))
            varIndex += paramType.size
        }
    }

    private fun methodParameterLocalCount(
        targetDesc: String,
        isStaticTarget: Boolean,
    ): Int {
        var count = if (isStaticTarget) 0 else 1
        for (paramType in Type.getArgumentTypes(targetDesc)) {
            count += paramType.size
        }
        return count
    }

    private fun wrapMethodHandlerOpcode(handlerMethod: Method): Int =
        if (Modifier.isStatic(handlerMethod.modifiers)) {
            Opcodes.INVOKESTATIC
        } else {
            Opcodes.INVOKEVIRTUAL
        }

    private fun toWrappedOriginalAccess(originalAccess: Int): Int =
        (originalAccess and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED).inv() and
            Opcodes.ACC_ABSTRACT.inv() and Opcodes.ACC_NATIVE.inv()) or
            Opcodes.ACC_PRIVATE or Opcodes.ACC_SYNTHETIC

    private fun nextWrappedOriginalMethodName(
        originalName: String,
        methodDesc: String,
    ): String {
        val baseName = "\$asm\$wrapMethod\$original\$$originalName\$${methodDesc.hashCode().toUInt().toString(16)}"
        var candidate = baseName
        var index = 0
        while (classNode.methods.any { it.name == candidate && it.desc == methodDesc }) {
            index++
            candidate = "$baseName\$$index"
        }
        return candidate
    }

    private fun isAsmKotlinObject(): Boolean =
        try {
            val instanceField = asmInfo.asmClass.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            instanceField.get(null) != null
        } catch (_: NoSuchFieldException) {
            false
        }

    private fun isWrapMethodParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) {
            return true
        }
        if (!expected.isReferenceType() || !actual.isReferenceType()) {
            return false
        }
        if (actual.sort == Type.OBJECT &&
            (actual.internalName == "java/lang/Object" || actual.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val expectedClass = loadReferenceClass(expected)
            loadReferenceClass(actual).isAssignableFrom(expectedClass)
        }.getOrDefault(false)
    }

    private fun isWrapMethodReturnCompatible(
        original: Type,
        handler: Type,
    ): Boolean {
        if (original == Type.VOID_TYPE) {
            return handler == Type.VOID_TYPE
        }
        if (handler == Type.VOID_TYPE) {
            return false
        }
        if (original == handler) {
            return true
        }
        return (original.sort == Type.OBJECT || original.sort == Type.ARRAY) && handler.sort >= Type.ARRAY
    }

    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    private fun loadReferenceClass(type: Type): Class<*> {
        val className =
            if (type.sort == Type.ARRAY) {
                type.descriptor.replace('/', '.')
            } else {
                type.className
            }
        val classLoader = asmInfo.asmClass.classLoader ?: ClassLoader.getSystemClassLoader()
        return Class.forName(className, false, classLoader)
    }

    /**
     * 收集父类和接口的方法信息
     */
    private fun collectParentMethods(): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()

        // 尝试加载父类
        if (classNode.superName != null && classNode.superName != "java/lang/Object") {
            try {
                val parentClassNode = loadParentClass(classNode.superName)
                if (parentClassNode != null) {
                    val methods = parentClassNode.methods.map { "${it.name}${it.desc}" }
                    result[classNode.superName] = methods
                }
            } catch (e: Exception) {
                // 忽略加载失败
            }
        }

        // 尝试加载接口
        for (interfaceName in classNode.interfaces) {
            try {
                val interfaceClassNode = loadParentClass(interfaceName)
                if (interfaceClassNode != null) {
                    val methods = interfaceClassNode.methods.map { "${it.name}${it.desc}" }
                    result[interfaceName] = methods
                }
            } catch (e: Exception) {
                // 忽略加载失败
            }
        }

        return result
    }

    /**
     * 加载父类的 ClassNode
     */
    private fun loadParentClass(parentClassName: String): ClassNode? {
        return try {
            val resource = parentClassName + ".class"
            val inputStream =
                asmInfo.asmClass.classLoader?.getResourceAsStream(resource)
                    ?: ClassLoader.getSystemClassLoader().getResourceAsStream(resource)
                    ?: return null

            val bytes = inputStream.use { it.readBytes() }
            val reader = ClassReader(bytes)
            val classNode = ClassNode()
            reader.accept(classNode, ClassReader.EXPAND_FRAMES)
            classNode
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 应用 @ModifyReturnValue 修改返回值
     */
    private fun applyModifyReturnValue(
        method: Method,
        annotation: ModifyReturnValue,
    ): Boolean {
        val (targetMethod, methodSignature) = resolveModifyReturnValueTargetMethod(method, annotation)
        val injector = AsmInjectorFactory.createModifyReturnValueInjector(method, asmInfo, annotation.ordinal)
        val injectionCount = injector.injectCount(targetMethod)
        if (annotation.require > 0 || annotation.allow >= 0 || annotation.expect != 1) {
            return requireModifyReturnValueCount(
                injectionCount,
                annotation,
                method,
                methodSignature,
            )
        }
        return requireInjectorMatched(
            injectionCount > 0,
            "@ModifyReturnValue",
            method,
            methodSignature,
        )
    }

    private fun resolveModifyReturnValueTargetMethod(
        method: Method,
        annotation: ModifyReturnValue,
    ): Pair<MethodNode, String> {
        if (annotation.method.isNotEmpty()) {
            val methodSignature = annotation.method
            return requireTargetMethod(methodSignature) to methodSignature
        }

        val compatibleTargets =
            classNode.methods.filter { candidate ->
                candidate.name == method.name &&
                    runCatching {
                        validateModifyReturnValueHandlerSignature(method, candidate)
                    }.isSuccess
            }

        if (compatibleTargets.isEmpty()) {
            throw IllegalStateException(buildMissingTargetMethodMessage(method.name))
        }
        if (compatibleTargets.size > 1) {
            val candidates = compatibleTargets.joinToString(", ") { "${it.name}${it.desc}" }
            throw IllegalStateException(
                "@ModifyReturnValue handler ${method.name} matches multiple target methods in $className: [$candidates]. " +
                    "Specify method explicitly to disambiguate.",
            )
        }

        val targetMethod = compatibleTargets.single()
        return targetMethod to "${targetMethod.name}${targetMethod.desc}"
    }

    private fun validateModifyReturnValueHandlerSignature(
        handlerMethod: Method,
        targetMethod: MethodNode,
    ) {
        val targetReturnType = Type.getReturnType(targetMethod.desc)
        if (targetReturnType == Type.VOID_TYPE) {
            throw IllegalArgumentException("@ModifyReturnValue cannot modify void target ${targetMethod.name}${targetMethod.desc}")
        }

        val handlerReturnType = Type.getReturnType(handlerMethod)
        if (!isModifyReturnValueReturnCompatible(targetReturnType, handlerReturnType, handlerMethod.returnType)) {
            throw IllegalArgumentException(
                "@ModifyReturnValue handler ${handlerMethod.name} return type $handlerReturnType " +
                    "must be compatible with target return type $targetReturnType",
            )
        }

        val handlerParamTypes = Type.getArgumentTypes(handlerMethod)
        val firstParamIsReturnValue =
            handlerParamTypes.isNotEmpty() && isModifyReturnValueParameterCompatible(targetReturnType, handlerParamTypes[0])
        val targetParamStart = if (firstParamIsReturnValue) 1 else 0
        val requestedTargetParamCount = handlerParamTypes.size - targetParamStart
        val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyReturnValue handler ${handlerMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${targetMethod.name}${targetMethod.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = handlerParamTypes[targetParamStart + index]
            if (!isModifyReturnValueParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyReturnValue handler ${handlerMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }
    }

    private fun isModifyReturnValueParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) {
            return true
        }
        if (!expected.isReferenceType() || !actual.isReferenceType()) {
            return false
        }
        if (actual.sort == Type.OBJECT &&
            (actual.internalName == "java/lang/Object" || actual.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val expectedClass = loadReferenceClass(expected)
            loadReferenceClass(actual).isAssignableFrom(expectedClass)
        }.getOrDefault(false)
    }

    private fun isModifyReturnValueReturnCompatible(
        targetReturnType: Type,
        handlerReturnType: Type,
        handlerReturnClass: Class<*>,
    ): Boolean {
        if (targetReturnType == handlerReturnType) {
            return true
        }
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (!targetReturnType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val targetClass = loadReferenceClass(targetReturnType)
            targetClass.isAssignableFrom(handlerReturnClass)
        }.getOrDefault(false)
    }

    /**
     * 查找目标方法
     * 注意：即使方法已经被 @ReplaceAllMethods 替换，也应该能够找到并覆写
     * 注意：即使方法是抽象的（ACC_ABSTRACT），也应该能够找到并覆写
     *       OverwriteInjector 会自动移除 ACC_ABSTRACT 和 ACC_NATIVE 标志
     */
    private fun findTargetMethod(methodSignature: String): MethodNode? {
        val (methodName, methodDesc) = parseMethodSignature(methodSignature)

        // 首先尝试精确匹配（包含描述符）
        if (methodDesc.isNotEmpty()) {
            val exactMatch =
                classNode.methods.find { method ->
                    method.name == methodName && method.desc == methodDesc
                }
            if (exactMatch != null) {
                return exactMatch
            }
        }

        // 如果没有找到精确匹配，尝试只匹配方法名（用于向后兼容）
        return classNode.methods.find { method ->
            method.name == methodName && (methodDesc.isEmpty() || method.desc == methodDesc)
        }
    }

    private fun requireTargetMethod(methodSignature: String): MethodNode =
        findTargetMethod(methodSignature)
            ?: throw IllegalStateException(buildMissingTargetMethodMessage(methodSignature))

    private fun requireTargetField(fieldName: String): FieldNode =
        classNode.fields.find { it.name == fieldName }
            ?: throw IllegalStateException(buildMissingTargetFieldMessage(fieldName))

    private fun requireAsmInjectCount(
        injectionCount: Int,
        annotation: AsmInject,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@AsmInject handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@AsmInject handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @AsmInject handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireModifyArgCount(
        injectionCount: Int,
        annotation: ModifyArg,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@ModifyArg handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@ModifyArg handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @ModifyArg handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireModifyArgsCount(
        injectionCount: Int,
        annotation: ModifyArgs,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@ModifyArgs handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@ModifyArgs handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @ModifyArgs handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireModifyExpressionValueCount(
        injectionCount: Int,
        annotation: ModifyExpressionValue,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@ModifyExpressionValue handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@ModifyExpressionValue handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @ModifyExpressionValue handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireModifyReceiverCount(
        injectionCount: Int,
        annotation: ModifyReceiver,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@ModifyReceiver handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@ModifyReceiver handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @ModifyReceiver handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireWrapOperationCount(
        injectionCount: Int,
        annotation: WrapOperation,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@WrapOperation handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@WrapOperation handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @WrapOperation handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireWrapMethodCount(
        injectionCount: Int,
        annotation: WrapMethod,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@WrapMethod handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@WrapMethod handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @WrapMethod handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireWrapWithConditionCount(
        injectionCount: Int,
        annotation: WrapWithCondition,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@WrapWithCondition handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@WrapWithCondition handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @WrapWithCondition handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireModifyVariableCount(
        injectionCount: Int,
        annotation: ModifyVariable,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@ModifyVariable handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@ModifyVariable handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @ModifyVariable handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireModifyReturnValueCount(
        injectionCount: Int,
        annotation: ModifyReturnValue,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@ModifyReturnValue handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@ModifyReturnValue handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @ModifyReturnValue handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireModifyConstantCount(
        injectionCount: Int,
        annotation: ModifyConstant,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@ModifyConstant handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@ModifyConstant handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @ModifyConstant handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireRedirectCount(
        injectionCount: Int,
        annotation: Redirect,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        val requiredCount = if (annotation.require > 0) annotation.require else 1
        if (injectionCount < requiredCount) {
            throw IllegalStateException(
                "@Redirect handler ${method.name} requires at least $requiredCount injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.allow >= 0 && injectionCount > annotation.allow) {
            throw IllegalStateException(
                "@Redirect handler ${method.name} allows at most ${annotation.allow} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        if (annotation.expect >= 0 && annotation.expect != 1 && injectionCount != annotation.expect) {
            System.err.println(
                "Warning: @Redirect handler ${method.name} expected ${annotation.expect} injection(s), " +
                    "actual $injectionCount in target method $targetMethodSignature of class $className",
            )
        }

        return injectionCount > 0
    }

    private fun requireInjectorMatched(
        transformed: Boolean,
        annotationName: String,
        method: Method,
        targetMethodSignature: String,
    ): Boolean {
        if (!transformed) {
            throw IllegalStateException(
                "$annotationName handler ${method.name} did not match any bytecode in " +
                    "target method $targetMethodSignature of class $className",
            )
        }
        return true
    }

    /**
     * 应用 @AddInterface 追加接口。
     *
     * 只改写目标类的接口声明列表；接口方法实现由其他 ASM 操作或目标类已有方法负责。
     */
    private fun applyAddInterface(annotation: AddInterface): Boolean {
        val interfaceNames = normalizeInterfaceNames(annotation.value, annotation.interfaces)

        var transformed = false
        for (interfaceName in interfaceNames) {
            if (!classNode.interfaces.contains(interfaceName)) {
                classNode.interfaces.add(interfaceName)
                transformed = true
            }
        }

        return transformed
    }

    /**
     * 应用 @RemoveInterface 移除接口。
     *
     * 只改写目标类的接口声明列表；不会删除目标类中已有的方法实现。
     */
    private fun applyRemoveInterface(annotation: RemoveInterface): Boolean {
        val interfaceNames = normalizeInterfaceNames(annotation.value, annotation.interfaces).toSet()
        val originalSize = classNode.interfaces.size

        classNode.interfaces.removeAll(interfaceNames)

        return classNode.interfaces.size != originalSize
    }

    private fun normalizeInterfaceNames(
        value: String,
        interfaces: Array<String>,
    ): List<String> =
        buildList {
            if (value.isNotEmpty()) {
                add(value)
            }
            addAll(interfaces.filter { it.isNotEmpty() })
        }.map { it.replace('.', '/') }
            .distinct()

    /**
     * 解析方法签名
     * 格式: methodName(Ljava/lang/String;)V 或 methodName
     */
    private fun parseMethodSignature(signature: String): Pair<String, String> {
        if (signature.isEmpty()) {
            return Pair("", "")
        }

        val parenIndex = signature.indexOf('(')
        return if (parenIndex > 0) {
            val methodName = signature.substring(0, parenIndex)
            val desc = signature.substring(parenIndex)
            Pair(methodName, desc)
        } else {
            Pair(signature, "")
        }
    }

    /**
     * 应用 @RemoveField 移除字段。
     */
    private fun applyRemoveField(
        field: java.lang.reflect.Field,
        annotation: RemoveField,
    ): Boolean {
        val targetFieldName =
            annotation.field.ifEmpty {
                field.name
            }
        val targetField = requireTargetField(targetFieldName)

        classNode.fields.remove(targetField)
        return true
    }

    /**
     * 应用 @RemoveField 移除字段。
     */
    private fun applyRemoveField(
        method: Method,
        annotation: RemoveField,
    ): Boolean {
        val targetFieldName =
            annotation.field.ifEmpty {
                inferFieldNameFromRemoveFieldMethod(method.name)
            }
        val targetField = requireTargetField(targetFieldName)

        classNode.fields.remove(targetField)
        return true
    }

    /**
     * 应用 @RemoveMethod 移除方法
     */
    private fun applyRemoveMethod(
        method: Method,
        annotation: RemoveMethod,
    ): Boolean {
        val methodSignature =
            if (annotation.method.isEmpty()) {
                // 如果注解中没有指定方法，则使用 ASM 方法名和描述符
                buildMethodSignature(method)
            } else {
                annotation.method
            }

        val targetMethod = findTargetMethod(methodSignature)
            ?: throw IllegalStateException(buildMissingTargetMethodMessage(methodSignature))

        // 从类中移除方法
        classNode.methods.remove(targetMethod)
        return true
    }

    /**
     * 应用 @RemoveSynchronized 移除方法同步
     */
    private fun applyRemoveSynchronized(
        method: Method,
        annotation: RemoveSynchronized,
    ): Boolean {
        val methodSignature =
            if (annotation.method.isEmpty()) {
                // 如果注解中没有指定方法，则使用 ASM 方法名和描述符
                buildMethodSignature(method)
            } else {
                annotation.method
            }

        val targetMethod = findTargetMethod(methodSignature)
            ?: throw IllegalStateException(buildMissingTargetMethodMessage(methodSignature))

        removeSynchronizedSemantics(targetMethod)

        return true
    }

    /**
     * 移除方法级 synchronized 标志和块级 monitor 指令。
     *
     * monitor 指令会消费栈顶对象引用；替换为 POP 可以保留原有加载指令的栈平衡。
     */
    private fun removeSynchronizedSemantics(methodNode: MethodNode) {
        val instructions = methodNode.instructions
        for (insnNode in instructions.toArray()) {
            if (insnNode.opcode == Opcodes.MONITORENTER || insnNode.opcode == Opcodes.MONITOREXIT) {
                instructions[insnNode] = InsnNode(Opcodes.POP)
            }
        }

        methodNode.access = methodNode.access and Opcodes.ACC_SYNCHRONIZED.inv()
    }

    private fun buildMissingTargetMethodMessage(methodSignature: String): String {
        val availableMethods = classNode.methods.joinToString(", ") { "${it.name}${it.desc}" }
        val parentMethods = collectParentMethods()
        return buildString {
            append("Cannot find target method $methodSignature in class $className for asm ${asmInfo.asmClass.name}.\n")
            append("  Available methods in $className: [$availableMethods]\n")
            if (parentMethods.isNotEmpty()) {
                append("  Parent class methods:\n")
                parentMethods.forEach { (parentName, methods) ->
                    append("    $parentName: [${methods.joinToString(", ")}]\n")
                }
            } else if (classNode.superName != null && classNode.superName != "java/lang/Object") {
                append("  Note: Parent class is ${classNode.superName}, but methods could not be loaded.\n")
            }
        }
    }

    private fun buildMissingTargetFieldMessage(fieldName: String): String {
        val availableFields = classNode.fields.joinToString(", ") { "${it.name}:${it.desc}" }
        return "Cannot find target field $fieldName in class $className for asm ${asmInfo.asmClass.name}.\n" +
            "  Available fields in $className: [$availableFields]\n"
    }

    private fun inferFieldNameFromRemoveFieldMethod(methodName: String): String =
        when {
            methodName.startsWith("remove") && methodName.length > 6 -> {
                methodName.substring(6).replaceFirstChar { it.lowercaseChar() }
            }
            methodName.startsWith("get") && methodName.length > 3 -> {
                methodName.substring(3).replaceFirstChar { it.lowercaseChar() }
            }
            methodName.startsWith("set") && methodName.length > 3 -> {
                methodName.substring(3).replaceFirstChar { it.lowercaseChar() }
            }
            methodName.startsWith("is") && methodName.length > 2 -> {
                methodName.substring(2).replaceFirstChar { it.lowercaseChar() }
            }
            else -> methodName
        }

    /**
     * 构建方法签名
     * 从 Java Method 对象构建 ASM 方法签名
     */
    private fun buildMethodSignature(method: Method): String {
        val methodName = method.name
        val methodDesc = Type.getMethodDescriptor(method)
        return "$methodName$methodDesc"
    }

    /**
     * 应用 @ReplaceAllMethods 全方法替换
     */
    private fun applyReplaceAllMethods(annotation: ReplaceAllMethods): Boolean {
        val isInterface = (classNode.access and Opcodes.ACC_INTERFACE) != 0

        // 如果不是接口，移除 abstract 修饰符
        if (!isInterface) {
            classNode.access = classNode.access and Opcodes.ACC_ABSTRACT.inv()
        }

        // 将所有非静态字段设为非 final
        for (field in classNode.fields) {
            if ((field.access and Opcodes.ACC_STATIC) == 0) {
                field.access = field.access and Opcodes.ACC_FINAL.inv()
            }
        }

        var transformed = false
        val iterator = classNode.methods.listIterator()

        for (methodNode in iterator) {
            // 跳过无参构造函数（如果需要可以添加）
            if (methodNode.name == "<init>" && methodNode.desc == "()V") {
                methodNode.access = methodNode.access or Opcodes.ACC_PUBLIC
                methodNode.access = methodNode.access and (Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE).inv()
            }

            // 对于接口，只处理静态方法和非抽象方法
            if (isInterface && (methodNode.access and Opcodes.ACC_STATIC) == 0 && (methodNode.access and Opcodes.ACC_ABSTRACT) != 0) {
                continue
            }

            // 处理每个方法
            if (replaceMethod(methodNode, annotation.removeSync)) {
                transformed = true
            }
        }

        return transformed
    }

    /**
     * 替换单个方法
     */
    private fun replaceMethod(
        methodNode: MethodNode,
        removeSync: Boolean,
    ): Boolean {
        // 移除同步
        if (removeSync) {
            removeSynchronizedSemantics(methodNode)
        }

        // 移除 abstract 和 native 修饰符
        methodNode.access = methodNode.access and Opcodes.ACC_NATIVE.inv() and Opcodes.ACC_ABSTRACT.inv()

        // 清空异常处理块、局部变量表、参数信息
        methodNode.tryCatchBlocks = ArrayList()
        methodNode.localVariables = ArrayList()
        methodNode.parameters = ArrayList()

        val returnType = Type.getReturnType(methodNode.desc)
        val useDefaultReturn = shouldUseDefaultReturn(returnType)

        // 处理构造函数：在 RETURN 前注入
        if (methodNode.name == "<init>" && methodNode.desc.endsWith(")V")) {
            val newInstructions = InsnList()
            for (insnNode in methodNode.instructions) {
                if (insnNode.opcode == Opcodes.RETURN) {
                    if (!useDefaultReturn && returnType != Type.VOID_TYPE) {
                        // 在 RETURN 前注入 RedirectionReplaceApi 调用
                        val il = InsnList()
                        injectRedirection(classNode, methodNode, il)
                        newInstructions.add(il)
                    }
                }
                newInstructions.add(insnNode)
            }
            methodNode.instructions = newInstructions
            return true
        } else {
            // 普通方法：完全替换方法体
            val il = InsnList()
            if (useDefaultReturn) {
                loadDefaultReturnValue(returnType, il)
            } else if (returnType != Type.VOID_TYPE) {
                injectRedirection(classNode, methodNode, il)
            }
            il.add(InstructionUtil.makeReturn(returnType))
            methodNode.instructions = il
            return true
        }
    }

    /**
     * 注入 RedirectionReplaceApi 调用
     */
    private fun injectRedirection(
        cn: ClassNode,
        mn: MethodNode,
        il: InsnList,
    ): Type {
        val isStatic = Modifier.isStatic(mn.access)

        // 加载对象（静态方法加载类，实例方法加载 this）
        if (isStatic) {
            il.add(LdcInsnNode(Type.getType("L${cn.name};")))
        } else {
            il.add(VarInsnNode(Opcodes.ALOAD, 0))
        }

        // 加载方法描述符字符串
        il.add(LdcInsnNode("L${cn.name};${mn.name}${mn.desc}"))

        // 加载返回类型
        val returnType = Type.getReturnType(mn.desc)
        il.add(InstructionUtil.loadType(returnType))

        // 加载参数数组
        loadArgArray(mn.desc, il, isStatic)

        // 调用 RedirectionReplaceApi.invokeIgnore（全方法替换使用 invokeIgnore）
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(RedirectionReplaceApi::class.java),
                RedirectionReplace.METHOD_SPACE_NAME,
                RedirectionReplace.METHOD_DESC,
            ),
        )

        // 转换返回值类型（参考 ASM 的实现方式）
        // 对于对象类型（Type.OBJECT 或 Type.ARRAY），直接添加 CHECKCAST
        // 对于基本类型，使用 unbox 方法
        if (returnType == Type.VOID_TYPE) {
            // void 返回类型，弹出返回值
            il.add(InsnNode(Opcodes.POP))
        } else if (returnType.sort >= Type.ARRAY) {
            // 对象类型或数组类型，直接添加 CHECKCAST
            il.add(TypeInsnNode(Opcodes.CHECKCAST, returnType.internalName))
        } else {
            // 基本类型，使用 unbox 方法
            val unboxList = InstructionUtil.unbox(returnType)
            for (unboxInsn in unboxList) {
                il.add(unboxInsn)
            }
        }

        return returnType
    }

    /**
     * 加载参数数组
     */
    private fun loadArgArray(
        desc: String,
        il: InsnList,
        isStatic: Boolean,
    ) {
        val args = Type.getArgumentTypes(desc)
        il.add(LdcInsnNode(args.size))
        il.add(TypeInsnNode(Opcodes.ANEWARRAY, Type.getInternalName(Any::class.java)))

        var v = if (isStatic) 0 else 1
        for (i in args.indices) {
            il.add(InsnNode(Opcodes.DUP))
            val type = args[i]
            il.add(LdcInsnNode(i))
            il.add(InstructionUtil.loadParam(type, v))

            // double 和 long 占用两个寄存器
            if (type.sort == Type.DOUBLE || type.sort == Type.LONG) {
                v++
            }

            // 装箱
            val boxing = InstructionUtil.box(type)
            if (boxing != null) {
                il.add(boxing)
            }

            il.add(InsnNode(Opcodes.AASTORE))
            v++
        }
    }

    private fun shouldUseDefaultReturn(type: Type): Boolean {
        if (type == Type.VOID_TYPE) {
            return true
        }
        return when (type.sort) {
            Type.BOOLEAN,
            Type.BYTE,
            Type.SHORT,
            Type.INT,
            Type.LONG,
            Type.FLOAT,
            Type.DOUBLE,
            Type.CHAR -> true
            Type.OBJECT -> type.internalName == "java/lang/String" || type.internalName == "java/lang/CharSequence"
            else -> false
        }
    }

    private fun loadDefaultReturnValue(
        type: Type,
        il: InsnList,
    ) {
        when (type.sort) {
            Type.BOOLEAN,
            Type.BYTE,
            Type.SHORT,
            Type.INT -> il.add(InsnNode(Opcodes.ICONST_0))
            Type.CHAR -> il.add(LdcInsnNode('a'))
            Type.FLOAT -> il.add(InsnNode(Opcodes.FCONST_0))
            Type.LONG -> il.add(InsnNode(Opcodes.LCONST_0))
            Type.DOUBLE -> il.add(InsnNode(Opcodes.DCONST_0))
            Type.OBJECT ->
                if (type.internalName == "java/lang/String" || type.internalName == "java/lang/CharSequence") {
                    il.add(LdcInsnNode(""))
                } else {
                    throw IllegalStateException("Unsupported default object type: ${type.internalName}")
                }
            Type.VOID -> return
            else -> throw IllegalStateException("Unsupported type for default return: $type")
        }
    }

    /**
     * 应用 @RedirectAllMethods 全方法重定向
     * 将目标类所有方法中的指定调用重定向到 @Redirect 标注的方法
     */
    private fun applyRedirectAllMethods(annotation: RedirectAllMethods): Boolean {
        var transformed = false

        // 收集所有 @Redirect 注解的方法
        val redirectMethods = asmInfo.asmClass.declaredMethods.filter { method ->
            method.getAnnotation(Redirect::class.java) != null
        }

        if (redirectMethods.isEmpty()) {
            return false
        }

        // 获取目标类的所有方法（排除构造函数和静态初始化块）
        val targetMethods = classNode.methods.filter {
            it.name != "<init>" && it.name != "<clinit>"
        }

        // 为每个 @Redirect 方法应用到所有目标方法
        for (redirectMethod in redirectMethods) {
            val redirectAnnotation = redirectMethod.getAnnotation(Redirect::class.java) ?: continue

            // 构建重定向目标
            val redirectTarget = buildRedirectTarget(redirectAnnotation.target, redirectAnnotation.at.target)
            var redirectCount = 0

            // 为每个目标方法应用此重定向，并按整个目标类累计命中数契约。
            for (methodNode in targetMethods) {
                val injector =
                    AsmInjectorFactory.createRedirectInjector(
                        redirectMethod,
                        asmInfo,
                        redirectTarget,
                        redirectAnnotation.at.value,
                        redirectAnnotation.ordinal,
                        redirectAnnotation.slice,
                        redirectAnnotation.at.args,
                    )
                redirectCount += injector.injectCount(methodNode)
            }

            if (requireRedirectCount(redirectCount, redirectAnnotation, redirectMethod, "<all methods>")) {
                transformed = true
            }
        }

        return transformed
    }

    /**
     * 根据方法签名查找方法
     */
    private fun findMethodBySignature(signature: String): MethodNode? {
        return classNode.methods.find { method ->
            val methodSig = "${method.name}${method.desc}"
            methodSig == signature
        }
    }
}




