/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.Shift
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.injector.util.AsmMethodCallGenerator
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method

/**
 * Invoke 注入器
 * 在方法调用前后注入代码
 *
 * @author Dr (dr@der.kim)
 */
class InvokeInjector(
    method: Method,
    asmInfo: AsmInfo,
) : AbstractAsmInjector(method, asmInfo) {
    override fun inject(target: MethodNode): Boolean {
        // 从 @AsmInject 注解中获取 @At 信息
        val injectAnnotation =
            asmMethod.getAnnotation(AsmInject::class.java)
                ?: return false

        val at = injectAnnotation.at
        val targetMethodSignature = at.target

        if (targetMethodSignature.isEmpty()) {
            return false
        }

        // 解析目标方法签名
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(targetMethodSignature)

        if (targetOwner == null || targetName == null) {
            return false
        }

        val instructions = target.instructions
        var transformed = false
        val insns = instructions.toArray()

        // 查找所有匹配的方法调用
        for (insn in insns) {
            if (insn is MethodInsnNode) {
                if (matchesTargetMethod(insn, targetOwner, targetName, targetDesc)) {
                    when (at.shift) {
                        Shift.BEFORE -> {
                            injectBeforeCall(instructions, insn, target)
                            transformed = true
                        }
                        Shift.AFTER -> {
                            injectAfterCall(instructions, insn, target)
                            transformed = true
                        }
                        Shift.REPLACE -> {
                            replaceCall(instructions, insn, target)
                            transformed = true
                        }
                    }
                }
            }
        }

        return transformed
    }

    /**
     * 解析目标方法签名
     */
    private fun parseTargetMethod(signature: String): Triple<String?, String?, String?> {
        if (signature.isEmpty()) {
            return Triple(null, null, null)
        }

        val lastDot = signature.lastIndexOf('.')
        val lastSlash = signature.lastIndexOf('/')
        val parenIndex = signature.indexOf('(')

        if (parenIndex < 0) {
            return Triple(null, signature, null)
        }

        val methodName: String
        val owner: String?
        val desc: String

        val separator = if (lastDot > lastSlash) lastDot else lastSlash

        if (separator > 0 && separator < parenIndex) {
            // 包含类名
            owner = signature.substring(0, separator).replace('.', '/')
            methodName = signature.substring(separator + 1, parenIndex)
            desc = signature.substring(parenIndex)
        } else {
            // 只有方法名
            owner = null
            methodName = signature.substring(0, parenIndex)
            desc = signature.substring(parenIndex)
        }

        return Triple(owner, methodName, desc)
    }

    /**
     * 检查是否匹配目标方法
     */
    private fun matchesTargetMethod(
        insn: MethodInsnNode,
        targetOwner: String,
        targetName: String,
        targetDesc: String?,
    ): Boolean {
        // 检查所有者
        if (insn.owner != targetOwner) {
            return false
        }

        // 检查方法名
        if (insn.name != targetName) {
            return false
        }

        // 检查描述符（如果指定）
        if (targetDesc != null && targetDesc.isNotEmpty() && insn.desc != targetDesc) {
            return false
        }

        return true
    }

    /**
     * 在方法调用前注入
     */
    private fun injectBeforeCall(
        instructions: InsnList,
        callInsn: MethodInsnNode,
        targetMethod: MethodNode,
    ) {
        val il = InsnList()

        // 保存方法调用的参数到局部变量
        val paramTypes = Type.getArgumentTypes(callInsn.desc)
        val savedParams = mutableListOf<Int>()
        var varIndex = allocateVariablesForParams(targetMethod, paramTypes)

        // 保存所有参数（从右到左）
        for (i in paramTypes.indices.reversed()) {
            val paramType = paramTypes[i]
            val needsDoubleSlot = paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE

            // 保存参数
            saveParameter(il, paramType, varIndex)
            savedParams.add(0, varIndex)

            varIndex -= if (needsDoubleSlot) 2 else 1
        }

        // 如果是实例方法调用，保存实例引用
        var savedInstanceIndex: Int? = null
        if (callInsn.opcode != Opcodes.INVOKESTATIC) {
            savedInstanceIndex = varIndex
            il.add(VarInsnNode(Opcodes.ASTORE, savedInstanceIndex))
        }

        // 生成调用 ASM 方法的指令
        val mockTarget = createMockMethodNode(targetMethod, callInsn)
        AsmMethodCallGenerator.generateMethodCall(
            il,
            asmMethod,
            asmInfo,
            mockTarget,
            null,
        )

        // 恢复参数（从左到右）
        if (savedInstanceIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, savedInstanceIndex))
        }

        for (savedIndex in savedParams) {
            val paramIndex = savedParams.indexOf(savedIndex)
            val paramType = paramTypes[paramIndex]
            InstructionUtil.loadParam(paramType, savedIndex).let { il.add(it) }
        }

        // 在调用前插入
        instructions.insertBefore(callInsn, il)
    }

    /**
     * 在方法调用后注入
     */
    private fun injectAfterCall(
        instructions: InsnList,
        callInsn: MethodInsnNode,
        targetMethod: MethodNode,
    ) {
        // 查找调用后的位置
        var nextInsn = callInsn.next

        // 跳过调用本身（如果有返回值，跳过返回值）
        val returnType = Type.getReturnType(callInsn.desc)
        if (returnType != Type.VOID_TYPE) {
            // 返回值在栈顶，需要保存
            val il = InsnList()
            val returnVarIndex = allocateVariableForReturn(targetMethod, returnType)

            // 保存返回值
            saveReturnValue(il, returnType, returnVarIndex)

            // 生成调用 ASM 方法的指令
            val mockTarget = createMockMethodNode(targetMethod, callInsn)
            AsmMethodCallGenerator.generateMethodCall(
                il,
                asmMethod,
                asmInfo,
                mockTarget,
                null,
            )

            // 恢复返回值
            loadReturnValue(il, returnType, returnVarIndex)

            instructions.insertBefore(nextInsn, il)
        } else {
            // 无返回值，直接在调用后插入
            val il = InsnList()
            val mockTarget = createMockMethodNode(targetMethod, callInsn)
            AsmMethodCallGenerator.generateMethodCall(
                il,
                asmMethod,
                asmInfo,
                mockTarget,
                null,
            )
            instructions.insertBefore(nextInsn, il)
        }
    }

    /**
     * 替换方法调用
     */
    private fun replaceCall(
        instructions: InsnList,
        callInsn: MethodInsnNode,
        targetMethod: MethodNode,
    ) {
        val il = InsnList()

        // 保存参数
        val paramTypes = Type.getArgumentTypes(callInsn.desc)
        val savedParams = mutableListOf<Int>()
        var varIndex = allocateVariablesForParams(targetMethod, paramTypes)

        for (i in paramTypes.indices.reversed()) {
            val paramType = paramTypes[i]
            val needsDoubleSlot = paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE

            saveParameter(il, paramType, varIndex)
            savedParams.add(0, varIndex)

            varIndex -= if (needsDoubleSlot) 2 else 1
        }

        var savedInstanceIndex: Int? = null
        if (callInsn.opcode != Opcodes.INVOKESTATIC) {
            savedInstanceIndex = varIndex
            il.add(VarInsnNode(Opcodes.ASTORE, savedInstanceIndex))
        }

        // 调用 ASM 方法替换原调用
        val mockTarget = createMockMethodNode(targetMethod, callInsn)
        AsmMethodCallGenerator.generateMethodCall(
            il,
            asmMethod,
            asmInfo,
            mockTarget,
            null,
        )

        // 处理返回值类型转换
        val originalReturnType = Type.getReturnType(callInsn.desc)
        val asmReturnType = Type.getReturnType(asmMethod)

        if (asmReturnType != originalReturnType) {
            if (asmReturnType == Type.VOID_TYPE && originalReturnType != Type.VOID_TYPE) {
                // ASM 返回 void，但原方法需要返回值，加载默认值
                loadDefaultReturnValue(il, originalReturnType)
            } else if (asmReturnType != Type.VOID_TYPE && originalReturnType == Type.VOID_TYPE) {
                // ASM 返回了值，但原方法返回 void，弹出
                il.add(InsnNode(Opcodes.POP))
            } else if (asmReturnType != Type.VOID_TYPE && originalReturnType != Type.VOID_TYPE) {
                // 类型转换
                if (asmReturnType.sort != originalReturnType.sort) {
                    val unboxList = InstructionUtil.unbox(asmReturnType)
                    for (unboxInsn in unboxList) {
                        il.add(unboxInsn)
                    }
                    if (originalReturnType.sort in
                        setOf(
                            Type.BOOLEAN,
                            Type.BYTE,
                            Type.CHAR,
                            Type.SHORT,
                            Type.INT,
                            Type.LONG,
                            Type.FLOAT,
                            Type.DOUBLE,
                        )
                    ) {
                        InstructionUtil.box(originalReturnType)?.let { il.add(it) }
                    }
                } else if (originalReturnType.sort == Type.OBJECT || originalReturnType.sort == Type.ARRAY) {
                    il.add(TypeInsnNode(Opcodes.CHECKCAST, originalReturnType.internalName))
                }
            }
        }

        // 替换原始调用
        instructions.insertBefore(callInsn, il)
        instructions.remove(callInsn)
    }

    /**
     * 保存参数
     */
    private fun saveParameter(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
        when (paramType.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> {
                il.add(VarInsnNode(Opcodes.ISTORE, varIndex))
            }
            Type.LONG -> {
                il.add(VarInsnNode(Opcodes.LSTORE, varIndex))
            }
            Type.FLOAT -> {
                il.add(VarInsnNode(Opcodes.FSTORE, varIndex))
            }
            Type.DOUBLE -> {
                il.add(VarInsnNode(Opcodes.DSTORE, varIndex))
            }
            else -> {
                il.add(VarInsnNode(Opcodes.ASTORE, varIndex))
            }
        }
    }

    /**
     * 保存返回值
     */
    private fun saveReturnValue(
        il: InsnList,
        returnType: Type,
        varIndex: Int,
    ) {
        saveParameter(il, returnType, varIndex)
    }

    /**
     * 加载返回值
     */
    private fun loadReturnValue(
        il: InsnList,
        returnType: Type,
        varIndex: Int,
    ) {
        val loadInsn = InstructionUtil.loadParam(returnType, varIndex)
        il.add(loadInsn)
    }

    /**
     * 加载默认返回值
     */
    private fun loadDefaultReturnValue(
        il: InsnList,
        returnType: Type,
    ) {
        when (returnType.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> {
                il.add(InsnNode(Opcodes.ICONST_0))
            }
            Type.LONG -> {
                il.add(InsnNode(Opcodes.LCONST_0))
            }
            Type.FLOAT -> {
                il.add(InsnNode(Opcodes.FCONST_0))
            }
            Type.DOUBLE -> {
                il.add(InsnNode(Opcodes.DCONST_0))
            }
            else -> {
                il.add(InsnNode(Opcodes.ACONST_NULL))
            }
        }
    }

    /**
     * 为参数分配局部变量
     */
    private fun allocateVariablesForParams(
        targetMethod: MethodNode,
        paramTypes: Array<Type>,
    ): Int {
        val isStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0
        var maxIndex = if (isStatic) 0 else 1

        val methodParamTypes = Type.getArgumentTypes(targetMethod.desc)
        for (paramType in methodParamTypes) {
            maxIndex += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
        }

        for (localVar in targetMethod.localVariables) {
            val end = localVar.index + (if (needsDoubleSlot(localVar.desc)) 2 else 1)
            maxIndex = maxOf(maxIndex, end)
        }

        var neededSlots = 0
        for (paramType in paramTypes) {
            neededSlots += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
        }

        return maxIndex + neededSlots
    }

    /**
     * 为返回值分配局部变量
     */
    private fun allocateVariableForReturn(
        targetMethod: MethodNode,
        returnType: Type,
    ): Int {
        val isStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0
        var maxIndex = if (isStatic) 0 else 1

        val methodParamTypes = Type.getArgumentTypes(targetMethod.desc)
        for (paramType in methodParamTypes) {
            maxIndex += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
        }

        for (localVar in targetMethod.localVariables) {
            val end = localVar.index + (if (needsDoubleSlot(localVar.desc)) 2 else 1)
            maxIndex = maxOf(maxIndex, end)
        }

        return maxIndex
    }

    /**
     * 创建模拟方法节点
     */
    private fun createMockMethodNode(
        targetMethod: MethodNode,
        callInsn: MethodInsnNode,
    ): MethodNode {
        val paramTypes = Type.getArgumentTypes(callInsn.desc)
        val returnType = Type.getReturnType(asmMethod)
        val mockDesc = Type.getMethodDescriptor(returnType, *paramTypes)
        return MethodNode(
            targetMethod.access,
            targetMethod.name,
            mockDesc,
            targetMethod.signature,
            targetMethod.exceptions?.toTypedArray(),
        )
    }

    /**
     * 检查是否需要双槽
     */
    private fun needsDoubleSlot(desc: String): Boolean = desc == "J" || desc == "D"
}
