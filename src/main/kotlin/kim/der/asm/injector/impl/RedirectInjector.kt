/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.injector.util.AsmMethodCallGenerator
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method

/**
 * Redirect 注入器
 * 重定向方法调用
 *
 * @author Dr (dr@der.kim)
 */
class RedirectInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val targetMethodSignature: String,
) : AbstractAsmInjector(method, asmInfo) {
    override fun inject(target: MethodNode): Boolean {
        // 解析目标方法签名
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(targetMethodSignature)

        if (targetOwner == null || targetName == null || targetDesc == null) {
            throw IllegalArgumentException("Invalid target method signature: $targetMethodSignature")
        }

        val instructions = target.instructions
        var transformed = false
        val insns = instructions.toArray()

        // 查找所有方法调用并替换
        for (insn in insns) {
            if (insn is MethodInsnNode) {
                // 检查是否匹配目标方法
                if (matchesTargetMethod(insn, targetOwner, targetName, targetDesc)) {
                    // 替换方法调用
                    replaceMethodCall(instructions, insn, target)
                    transformed = true
                }
            }
        }

        return transformed
    }

    /**
     * 解析目标方法签名
     * 格式: "com/example/Class.methodName(Ljava/lang/String;)V" 或 "methodName(Ljava/lang/String;)V"
     */
    private fun parseTargetMethod(signature: String): Triple<String?, String?, String?> {
        if (signature.isEmpty()) {
            return Triple(null, null, null)
        }

        val lastDot = signature.lastIndexOf('.')
        val parenIndex = signature.indexOf('(')

        if (parenIndex < 0) {
            return Triple(null, signature, null)
        }

        val methodName: String
        val owner: String?
        val desc: String

        if (lastDot > 0 && lastDot < parenIndex) {
            // 包含类名
            owner = signature.substring(0, lastDot).replace('.', '/')
            methodName = signature.substring(lastDot + 1, parenIndex)
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
        targetOwner: String?,
        targetName: String,
        targetDesc: String,
    ): Boolean {
        // 检查所有者（如果指定）
        if (targetOwner != null && insn.owner != targetOwner) {
            return false
        }

        // 检查方法名
        if (insn.name != targetName) {
            return false
        }

        // 检查描述符（如果指定）
        if (targetDesc.isNotEmpty() && insn.desc != targetDesc) {
            return false
        }

        return true
    }

    /**
     * 替换方法调用
     */
    private fun replaceMethodCall(
        instructions: InsnList,
        originalInsn: MethodInsnNode,
        target: MethodNode,
    ) {
        val il = InsnList()

        // 保存调用参数到局部变量（如果需要）
        val paramTypes = Type.getArgumentTypes(originalInsn.desc)
        val savedParams = mutableListOf<Int>()
        var varIndex = allocateVariablesForParams(target, paramTypes)

        // 保存所有参数
        var paramOffset = 0
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
        if (originalInsn.opcode != Opcodes.INVOKESTATIC) {
            savedInstanceIndex = varIndex
            il.add(VarInsnNode(Opcodes.ASTORE, savedInstanceIndex))
            varIndex++
        }

        // 生成调用 ASM 方法的指令
        // 需要加载所有保存的参数
        if (savedInstanceIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, savedInstanceIndex))
        }

        for (savedIndex in savedParams) {
            val paramIndex = savedParams.indexOf(savedIndex)
            val paramType = paramTypes[paramIndex]
            InstructionUtil.loadParam(paramType, savedIndex).let { il.add(it) }
        }

        // 创建模拟方法节点
        val mockTarget = createMockMethodNode(target, originalInsn)
        AsmMethodCallGenerator.generateMethodCall(
            il,
            asmMethod,
            asmInfo,
            mockTarget,
            null,
        )

        // 处理返回值类型转换（如果需要）
        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val asmReturnType = Type.getReturnType(asmMethod)

        if (asmReturnType != originalReturnType && asmReturnType != Type.VOID_TYPE) {
            if (originalReturnType == Type.VOID_TYPE) {
                // ASM 返回了值但原方法返回 void，需要弹出
                il.add(InsnNode(Opcodes.POP))
            } else {
                // 类型转换
                if (asmReturnType.sort != originalReturnType.sort) {
                    val unboxList = InstructionUtil.unbox(asmReturnType)
                    for (unboxInsn in unboxList) {
                        il.add(unboxInsn)
                    }
                    // 装箱回目标类型（如果需要）
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
                } else {
                    // 直接类型转换
                    if (originalReturnType.sort == Type.OBJECT || originalReturnType.sort == Type.ARRAY) {
                        il.add(TypeInsnNode(Opcodes.CHECKCAST, originalReturnType.internalName))
                    }
                }
            }
        }

        // 替换原始调用
        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)

        // 清理临时变量（在方法结束时自动清理）
    }

    /**
     * 保存参数到局部变量
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
     * 为参数分配局部变量
     */
    private fun allocateVariablesForParams(
        targetMethod: MethodNode,
        paramTypes: Array<Type>,
    ): Int {
        val isStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0
        var maxIndex = if (isStatic) 0 else 1

        // 计算所有参数占用的局部变量
        val methodParamTypes = Type.getArgumentTypes(targetMethod.desc)
        for (paramType in methodParamTypes) {
            maxIndex += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
        }

        // 查找最大局部变量索引
        for (localVar in targetMethod.localVariables) {
            val end = localVar.index + (if (needsDoubleSlot(localVar.desc)) 2 else 1)
            maxIndex = maxOf(maxIndex, end)
        }

        // 计算需要分配的变量数
        var neededSlots = 0
        for (paramType in paramTypes) {
            neededSlots += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
        }

        return maxIndex + neededSlots
    }

    /**
     * 创建模拟方法节点
     */
    private fun createMockMethodNode(
        target: MethodNode,
        originalInsn: MethodInsnNode,
    ): MethodNode {
        val paramTypes = Type.getArgumentTypes(originalInsn.desc)
        val returnType = Type.getReturnType(asmMethod)
        val mockDesc = Type.getMethodDescriptor(returnType, *paramTypes)
        return MethodNode(
            target.access,
            target.name,
            mockDesc,
            target.signature,
            target.exceptions?.toTypedArray(),
        )
    }

    /**
     * 检查是否需要双槽
     */
    private fun needsDoubleSlot(desc: String): Boolean = desc == "J" || desc == "D"
}
