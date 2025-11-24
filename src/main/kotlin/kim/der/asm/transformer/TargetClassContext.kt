/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.transformer

import kim.der.asm.api.annotation.*
import kim.der.asm.api.replace.RedirectionReplace
import kim.der.asm.api.replace.RedirectionReplaceApi
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AsmInjectorFactory
import kim.der.asm.injector.util.InlineCodeGenerator
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 目标类上下文
 * 管理将 ASM 应用到目标类的过程
 *
 * @author Dr (dr@der.kim)
 */
class TargetClassContext(
    private val className: String,
    private val classNode: ClassNode,
    private val asmInfo: AsmInfo,
) {
    /**
     * 应用 ASM
     *
     * @return true 如果进行了转换
     */
    fun applyAsm(): Boolean {
        var transformed = false

        // 检查是否需要为 class 类型的 Mixin 创建静态字段
        ensureSingletonField()

        // 检查是否有 @ReplaceAllMethods 注解
        val replaceAllAnnotation = asmInfo.asmClass.getAnnotation(ReplaceAllMethods::class.java)
        if (replaceAllAnnotation != null) {
            if (applyReplaceAllMethods(replaceAllAnnotation)) {
                transformed = true
            }
        }

        // 处理 ASM 类中的所有字段
        applyFields()

        // 收集所有需要处理的方法，按注入点类型分组
        // 注意：必须按照特定的顺序处理注入，以确保行为一致
        val methodsToProcess = asmInfo.asmClass.declaredMethods.toList()

        /**
         * 第一轮：处理 RETURN 和 TAIL 注入（必须在 HEAD 注入之前）
         *
         * 原因：
         * 1. HEAD 注入如果取消方法执行，会在取消分支中创建一个新的 RETURN 指令
         * 2. RETURN 注入会查找所有 RETURN 指令并在其之前插入代码
         * 3. 如果 RETURN 注入在 HEAD 注入之后执行，它会错误地在 HEAD 注入创建的 RETURN 指令之前插入代码
         * 4. 这会导致即使 HEAD 注入取消了方法，RETURN 注入仍然会被执行（不符合预期）
         *
         * 解决方案：
         * - 先处理 RETURN/TAIL 注入，它们只会在原始的 RETURN 指令之前插入代码
         * - 后处理 HEAD 注入，HEAD 注入创建的 RETURN 指令不会被 RETURN 注入处理
         * - 这样确保：当 HEAD 注入取消方法时，RETURN 注入不会被执行（符合 Mixin-master 的行为）
         */
        for (method in methodsToProcess) {
            val injectAnnotation = method.getAnnotation(AsmInject::class.java)
            if (injectAnnotation != null &&
                (injectAnnotation.target == InjectionPoint.RETURN || injectAnnotation.target == InjectionPoint.TAIL)
            ) {
                if (applyInject(method, injectAnnotation)) {
                    transformed = true
                }
            }
        }

        /**
         * 处理其他非 HEAD 注入（如 INVOKE 等）
         * 这些注入不涉及 RETURN 指令的创建，可以在 HEAD 注入之前或之后处理
         */
        for (method in methodsToProcess) {
            val injectAnnotation = method.getAnnotation(AsmInject::class.java)
            val modifyArgAnnotation = method.getAnnotation(ModifyArg::class.java)
            val redirectAnnotation = method.getAnnotation(Redirect::class.java)
            val overwriteAnnotation = method.getAnnotation(Overwrite::class.java)
            val copyAnnotation = method.getAnnotation(Copy::class.java)
            val modifyReturnValueAnnotation = method.getAnnotation(ModifyReturnValue::class.java)
            val modifyConstantAnnotation = method.getAnnotation(ModifyConstant::class.java)
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
                        if (applyInject(method, injectAnnotation)) {
                            transformed = true
                        }
                    }
                }
                modifyArgAnnotation != null -> {
                    if (applyModifyArg(method, modifyArgAnnotation)) {
                        transformed = true
                    }
                }
                redirectAnnotation != null -> {
                    if (applyRedirect(method, redirectAnnotation)) {
                        transformed = true
                    }
                }
                overwriteAnnotation != null -> {
                    if (applyOverwrite(method, overwriteAnnotation)) {
                        transformed = true
                    }
                }
                copyAnnotation != null -> {
                    if (applyCopy(method, copyAnnotation)) {
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

        /**
         * 第二轮：最后处理 HEAD 注入（确保在 RETURN 注入之后）
         *
         * 处理顺序的重要性：
         * - HEAD 注入在方法开头插入代码，如果取消方法执行，会在取消分支中创建 RETURN 指令
         * - 由于 RETURN 注入已经在第一轮处理完成，它只会处理原始的 RETURN 指令
         * - HEAD 注入创建的 RETURN 指令不会被 RETURN 注入处理
         * - 这确保了当 HEAD 注入取消方法时，RETURN 注入不会被执行（符合预期行为）
         *
         * 参考 Mixin-master 的行为：
         * - 当 HEAD 注入取消方法时，方法立即返回，不会执行方法体
         * - 因此 RETURN 注入不应该被执行（因为方法体从未执行）
         */
        for (method in methodsToProcess) {
            val injectAnnotation = method.getAnnotation(AsmInject::class.java)
            if (injectAnnotation != null && injectAnnotation.target == InjectionPoint.HEAD) {
                if (applyInject(method, injectAnnotation)) {
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
    private fun applyFields() {
        for (field in asmInfo.asmClass.declaredFields) {
            val shadowAnnotation = field.getAnnotation(Shadow::class.java)
            val mutableAnnotation = field.getAnnotation(Mutable::class.java)
            val finalAnnotation = field.getAnnotation(Final::class.java)

            if (shadowAnnotation != null) {
                applyShadowField(field, shadowAnnotation)
            }

            if (mutableAnnotation != null || finalAnnotation != null) {
                applyFieldModifiers(field, mutableAnnotation != null, finalAnnotation != null)
            }
        }
    }

    /**
     * 应用 Shadow 字段
     */
    private fun applyShadowField(
        field: java.lang.reflect.Field,
        annotation: Shadow,
    ) {
        val fieldName = field.name
        val method = annotation.method

        // 如果注解以 [Shadow.prefix] 开头，需要去掉 prefix
        val targetFieldName =
            if (method.startsWith(Shadow.prefix)) {
                method.substring(Shadow.prefix.length)
            } else {
                fieldName
            }

        // 查找目标字段
        val targetField = classNode.fields.find { it.name == targetFieldName }
        if (targetField != null) {
            // 如果字段存在，应用 Mutable 修饰符
            if (field.isAnnotationPresent(Mutable::class.java)) {
                targetField.access = targetField.access and Opcodes.ACC_FINAL.inv()
            }
        }
    }

    /**
     * 应用 Shadow 方法
     */
    private fun applyShadowMethod(
        method: Method,
        annotation: Shadow,
    ) {
        val methodName = method.name
        val prefix = annotation.method

        // 如果注解以 [Shadow.prefix] 开头，需要去掉 prefix
        val targetMethodName =
            if (prefix.startsWith(Shadow.prefix)) {
                prefix.substring(Shadow.prefix.length)
            } else {
                methodName
            }

        // 验证目标方法是否存在（Shadow 方法只是引用，不需要转换）
        val targetMethod = findTargetMethod("$targetMethodName${Type.getMethodDescriptor(method)}")
        if (targetMethod == null) {
            System.err.println("Warning: Shadow method $methodName not found in target class $className")
        }
    }

    /**
     * 应用字段修饰符
     */
    private fun applyFieldModifiers(
        field: java.lang.reflect.Field,
        mutable: Boolean,
        final: Boolean,
    ) {
        val targetField = classNode.fields.find { it.name == field.name }
        if (targetField != null) {
            if (mutable) {
                // 移除 FINAL 标志
                targetField.access = targetField.access and Opcodes.ACC_FINAL.inv()
            }
            if (final) {
                // 添加 FINAL 标志
                targetField.access = targetField.access or Opcodes.ACC_FINAL
            }
        }
    }

    /**
     * 应用 @ModifyConstant 修改常量
     */
    private fun applyModifyConstant(
        method: Method,
        annotation: ModifyConstant,
    ): Boolean {
        val targetMethod = findTargetMethod(annotation.method) ?: return false
        val injector =
            AsmInjectorFactory.createModifyConstantInjector(
                method,
                asmInfo,
                if (annotation.constant.isEmpty()) null else annotation.constant,
            )
        return injector.inject(targetMethod)
    }

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
            classNode.fields.find { it.name == targetFieldName }
                ?: throw IllegalStateException("Accessor target field $targetFieldName not found in $className")

        // 生成访问器方法
        val accessorMethod = generateAccessorMethod(method, targetField, targetFieldName)
        classNode.methods.add(accessorMethod)

        return true
    }

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
            findTargetMethod("$targetMethodName${Type.getMethodDescriptor(method)}")
                ?: throw IllegalStateException("Invoker target method $targetMethodName not found in $className")

        // 生成调用器方法
        val invokerMethod = generateInvokerMethod(method, targetMethod, targetMethodName)
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
        targetField: FieldNode,
        fieldName: String,
    ): MethodNode {
        val isGetter = asmMethod.parameterCount == 0
        val isStatic = (targetField.access and Opcodes.ACC_STATIC) != 0
        val fieldType = Type.getType(targetField.desc)
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
                il.add(FieldInsnNode(Opcodes.GETSTATIC, className, fieldName, targetField.desc))
            } else {
                il.add(VarInsnNode(Opcodes.ALOAD, 0))
                il.add(FieldInsnNode(Opcodes.GETFIELD, className, fieldName, targetField.desc))
            }
            il.add(InstructionUtil.makeReturn(fieldType))
        } else {
            // Setter: 接收参数并设置字段值
            // 如果字段是 final，需要先移除 final 标志（如果访问器方法有 @Mutable 注解）
            val mutable = asmMethod.isAnnotationPresent(Mutable::class.java)
            if (mutable && (targetField.access and Opcodes.ACC_FINAL) != 0) {
                targetField.access = targetField.access and Opcodes.ACC_FINAL.inv()
            }

            if (isStatic) {
                il.add(InstructionUtil.loadParam(fieldType, 0))
                il.add(FieldInsnNode(Opcodes.PUTSTATIC, className, fieldName, targetField.desc))
            } else {
                il.add(VarInsnNode(Opcodes.ALOAD, 0))
                il.add(InstructionUtil.loadParam(fieldType, 1))
                il.add(FieldInsnNode(Opcodes.PUTFIELD, className, fieldName, targetField.desc))
            }
            il.add(InsnNode(Opcodes.RETURN))
        }

        methodNode.instructions = il
        val fieldSize = if (fieldType.sort == Type.LONG || fieldType.sort == Type.DOUBLE) 2 else 1
        methodNode.maxLocals = fieldSize + (if (isStatic) 0 else 1)
        methodNode.maxStack = fieldSize + (if (isStatic) 0 else 1)

        return methodNode
    }

    /**
     * 生成调用器方法
     */
    private fun generateInvokerMethod(
        asmMethod: Method,
        targetMethod: MethodNode,
        methodName: String,
    ): MethodNode {
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
                    if (isSynthetic && isPrivate) Opcodes.INVOKESPECIAL else Opcodes.INVOKEINTERFACE
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
    ): Boolean {
        val targetMethod = findTargetMethod(annotation.method) ?: return false

        // 如果设置了 inline=true，则内联 ASM 方法的字节码
        if (annotation.inline) {
            return injectInlineCode(targetMethod, method, annotation)
        }

        // 否则使用普通的注入器在指定位置注入代码
        val injector = AsmInjectorFactory.createInjector(annotation.target, method, asmInfo)
        return injector.inject(targetMethod)
    }

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
    ): Boolean {
        val il =
            InlineCodeGenerator.inlineMethodCode(
                target,
                asmMethod,
                asmInfo,
                className,
            )

        // 根据注入点位置插入代码
        when (annotation.target) {
            InjectionPoint.HEAD -> {
                // 在方法开头插入
                // 注意：HEAD 注入在最后处理，所以此时 RETURN 注入已经完成
                if (target.instructions.size() == 0) {
                    target.instructions.add(il)
                } else {
                    target.instructions.insertBefore(target.instructions.first, il)
                }
            }
            InjectionPoint.TAIL -> {
                // 在所有 RETURN 之前插入
                // 注意：TAIL 注入在第一轮处理，只处理原始的 RETURN 指令
                val insns = target.instructions.toArray()
                for (insn in insns) {
                    if (insn is InsnNode && insn.opcode in RETURN_OPS) {
                        target.instructions.insertBefore(insn, il)
                        break
                    }
                }
                // 如果没有找到 RETURN，在末尾添加
                if (target.instructions.size() == 0 ||
                    target.instructions.toArray().none { it is InsnNode && (it as InsnNode).opcode in RETURN_OPS }
                ) {
                    target.instructions.add(il)
                }
            }
            InjectionPoint.RETURN -> {
                // 在每个 RETURN 之前插入
                // 注意：RETURN 注入在第一轮处理，只处理原始的 RETURN 指令
                // HEAD 注入创建的 RETURN 指令不会被处理（因为 HEAD 注入在 RETURN 注入之后执行）
                val insns = target.instructions.toArray()
                for (insn in insns) {
                    if (insn is InsnNode && insn.opcode in RETURN_OPS) {
                        // 为每个 RETURN 创建新的指令列表副本
                        val cloned = InsnList()
                        for (originalInsn in il) {
                            cloned.add(originalInsn.clone(null))
                        }
                        target.instructions.insertBefore(insn, cloned)
                    }
                }
            }
            else -> {
                // 默认在方法开头插入
                if (target.instructions.size() == 0) {
                    target.instructions.add(il)
                } else {
                    target.instructions.insertBefore(target.instructions.first, il)
                }
            }
        }

        return true
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
    }

    /**
     * 应用 @ModifyArg 修改参数
     */
    private fun applyModifyArg(
        method: Method,
        annotation: ModifyArg,
    ): Boolean {
        val targetMethod = findTargetMethod(annotation.method) ?: return false
        val injector = AsmInjectorFactory.createModifyArgInjector(method, asmInfo, annotation.index)
        return injector.inject(targetMethod)
    }

    /**
     * 应用 @Redirect 重定向
     */
    private fun applyRedirect(
        method: Method,
        annotation: Redirect,
    ): Boolean {
        val targetMethod = findTargetMethod(annotation.method) ?: return false

        // 组合 target 和 at.target 来构建完整的方法签名
        val redirectTarget = buildRedirectTarget(annotation.target, annotation.at.target)

        val injector = AsmInjectorFactory.createRedirectInjector(method, asmInfo, redirectTarget)
        return injector.inject(targetMethod)
    }

    /**
     * 构建 Redirect 目标方法签名
     * 如果 at.target 存在，则组合为 "owner/method(desc)"
     * 否则直接使用 target
     */
    private fun buildRedirectTarget(
        target: String,
        atTarget: String,
    ): String {
        if (atTarget.isEmpty()) {
            return target
        }

        // 如果 target 包含方法名和描述符，组合 owner
        // 格式: "methodName(desc)" 或 "owner.methodName(desc)"
        if (target.contains("(")) {
            val parenIndex = target.indexOf('(')
            val methodPart = target.substring(0, parenIndex)
            val descPart = target.substring(parenIndex)

            // 如果 methodPart 不包含点，说明没有 owner，需要添加
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
    ): Boolean {
        val targetMethod = findTargetMethod(annotation.method)
        if (targetMethod == null) {
            // 如果找不到目标方法，记录警告但继续处理其他 asm
            val availableMethods = classNode.methods.joinToString(", ") { "${it.name}${it.desc}" }
            val parentMethods = collectParentMethods()
            val errorMsg =
                buildString {
                    append(
                        "Warning: Cannot find target method ${annotation.method} in class $className for asm ${asmInfo.asmClass.name}.\n",
                    )
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
            System.err.println(errorMsg)
            return false
        }

        // 覆写方法：清空原方法体并替换为 asm 方法的内容
        // 这允许后续的 @Overwrite 再次覆写这个方法
        val injector = AsmInjectorFactory.createOverwriteInjector(method, asmInfo)
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

        // 检查目标方法是否已存在
        val existingMethod = classNode.methods.find { it.name == methodName && it.desc == methodDesc }
        if (existingMethod != null) {
            System.err.println(
                "Warning: Method $methodSignature already exists in class $className. " +
                    "Cannot copy method from ${asmInfo.asmClass.name}. Use @Overwrite to replace it.",
            )
            return false
        }

        // 创建目标方法节点（使用目标方法的签名）
        val targetMethod = MethodNode(
            Opcodes.ACC_PUBLIC, // 默认 public，可以根据需要调整
            methodName,
            methodDesc,
            null,
            null,
        )

        // 使用 CopyInjector 创建新方法
        val injector = AsmInjectorFactory.createCopyInjector(method, asmInfo)
        val newMethod = injector.createMethod(targetMethod)

        // 将新方法添加到目标类
        classNode.methods.add(newMethod)

        return true
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
        val targetMethod = findTargetMethod(annotation.method) ?: return false
        val injector = AsmInjectorFactory.createModifyReturnValueInjector(method, asmInfo)
        return injector.inject(targetMethod)
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

        val targetMethod = findTargetMethod(methodSignature) ?: return false

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

        val targetMethod = findTargetMethod(methodSignature) ?: return false

        // 移除同步指令
        val instructions = targetMethod.instructions
        for (insnNode in instructions.toArray()) {
            if (insnNode.opcode == Opcodes.MONITORENTER) {
                // 将 monitorEnter 替换为 pop，即将监视器弹出堆栈
                instructions[insnNode] = InsnNode(Opcodes.POP)
            }
        }

        // 移除 ACC_SYNCHRONIZED 标志
        targetMethod.access = targetMethod.access and Opcodes.ACC_SYNCHRONIZED.inv()

        return true
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
            // 移除同步指令
            val instructions = methodNode.instructions
            for (insnNode in instructions.toArray()) {
                if (insnNode.opcode == Opcodes.MONITORENTER) {
                    instructions[insnNode] = InsnNode(Opcodes.POP)
                }
            }
            // 移除 ACC_SYNCHRONIZED 标志
            methodNode.access = methodNode.access and Opcodes.ACC_SYNCHRONIZED.inv()
        }

        // 移除 abstract 和 native 修饰符
        methodNode.access = methodNode.access and Opcodes.ACC_NATIVE.inv() and Opcodes.ACC_ABSTRACT.inv()

        // 清空异常处理块、局部变量表、参数信息
        methodNode.tryCatchBlocks = ArrayList()
        methodNode.localVariables = ArrayList()
        methodNode.parameters = ArrayList()

        // 处理构造函数：在 RETURN 前注入
        if (methodNode.name == "<init>" && methodNode.desc.endsWith(")V")) {
            val newInstructions = InsnList()
            for (insnNode in methodNode.instructions) {
                if (insnNode.opcode == Opcodes.RETURN) {
                    // 在 RETURN 前注入 RedirectionReplaceApi 调用
                    val il = InsnList()
                    injectRedirection(classNode, methodNode, il)
                    newInstructions.add(il)
                }
                newInstructions.add(insnNode)
            }
            methodNode.instructions = newInstructions
            return true
        } else {
            // 普通方法：完全替换方法体
            val il = InsnList()
            val returnType = injectRedirection(classNode, methodNode, il)
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
}
