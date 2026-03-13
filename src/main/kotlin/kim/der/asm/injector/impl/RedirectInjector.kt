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

        if (targetName == null || targetDesc == null) {
            throw IllegalArgumentException(
                "Invalid target method signature: $targetMethodSignature " +
                "(parsed: owner=$targetOwner, name=$targetName, desc=$targetDesc)"
            )
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
     * 查找方法调用前加载参数和实例的指令
     */
    private fun findLoadInstructions(
        instructions: InsnList,
        methodInsn: MethodInsnNode
    ): List<AbstractInsnNode> {
        val paramTypes = Type.getArgumentTypes(methodInsn.desc)
        val isInstanceCall = methodInsn.opcode != Opcodes.INVOKESTATIC
        
        // 计算需要从栈上消耗的槽位数
        var slotsNeeded = paramTypes.sumOf { 
            if (it.sort == Type.LONG || it.sort == Type.DOUBLE) 2 else 1 
        }
        if (isInstanceCall) slotsNeeded++ // 实例引用占一个槽位
        
        val loadInsns = mutableListOf<AbstractInsnNode>()
        var currentInsn = methodInsn.previous
        var slotsFound = 0
        
        // 向后查找加载指令
        while (currentInsn != null && slotsFound < slotsNeeded) {
            val opcode = currentInsn.opcode
            when {
                // ALOAD variants (21, 42-45)
                opcode == Opcodes.ALOAD || opcode in 42..45 -> {
                    loadInsns.add(0, currentInsn)
                    slotsFound++
                }
                // ILOAD variants (21, 26-29)
                opcode == Opcodes.ILOAD || opcode in 26..29 -> {
                    loadInsns.add(0, currentInsn)
                    slotsFound++
                }
                // FLOAD variants (23, 34-37)
                opcode == Opcodes.FLOAD || opcode in 34..37 -> {
                    loadInsns.add(0, currentInsn)
                    slotsFound++
                }
                // LLOAD variants (22, 30-33) - takes 2 slots
                opcode == Opcodes.LLOAD || opcode in 30..33 -> {
                    loadInsns.add(0, currentInsn)
                    slotsFound += 2
                }
                // DLOAD variants (24, 38-41) - takes 2 slots
                opcode == Opcodes.DLOAD || opcode in 38..41 -> {
                    loadInsns.add(0, currentInsn)
                    slotsFound += 2
                }
                // 如果遇到方法调用或其他产生值的指令,停止查找
                opcode in listOf(
                    Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC,
                    Opcodes.INVOKEINTERFACE, Opcodes.INVOKEDYNAMIC,
                    Opcodes.NEW, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY,
                    Opcodes.GETFIELD, Opcodes.GETSTATIC,
                    Opcodes.LDC,
                    Opcodes.ACONST_NULL
                ) || opcode in 2..15 || opcode == 18 || opcode == 20 -> {
                    // 这些指令产生值,不是简单的load,停止查找
                    // opcodes 2-15: ICONST_*, LCONST_*, FCONST_*, DCONST_*
                    // opcode 18: LDC
                    // opcode 20: LDC2_W
                    return emptyList()
                }
            }
            currentInsn = currentInsn.previous
        }
        
        // 只有找到了足够的load指令才返回
        return if (slotsFound == slotsNeeded) loadInsns else emptyList()
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
        target: MethodNode
    ) {
        val il = InsnList()

        // 直接生成调用重定向处理器的指令
        // 栈上已经有了参数: [..., instance?, param1, param2, ...]
        // 重定向处理器必须是静态方法,直接调用即可
        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )

        // 处理返回值类型转换
        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val asmReturnType = Type.getReturnType(asmMethod)

        if (originalReturnType != asmReturnType) {
            if (originalReturnType == Type.VOID_TYPE && asmReturnType != Type.VOID_TYPE) {
                // 原方法返回 void 但重定向处理器返回了值,需要弹出
                il.add(InsnNode(Opcodes.POP))
            } else if (originalReturnType != Type.VOID_TYPE && asmReturnType == Type.VOID_TYPE) {
                // 重定向处理器返回 void 但原方法需要返回值,这是错误的
                throw IllegalStateException(
                    "Redirect handler returns void but original method expects ${originalReturnType.className}"
                )
            } else if (originalReturnType != Type.VOID_TYPE && asmReturnType != Type.VOID_TYPE) {
                // 两者都有返回值,需要类型转换
                if (originalReturnType.sort == Type.OBJECT || originalReturnType.sort == Type.ARRAY) {
                    il.add(TypeInsnNode(Opcodes.CHECKCAST, originalReturnType.internalName))
                }
            }
        }

        // 在原始调用位置插入新代码并移除原始调用
        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
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
     * 返回第一个可用的局部变量索引
     */
    private fun allocateVariablesForParams(
        targetMethod: MethodNode,
        paramTypes: Array<Type>,
        isInstanceCall: Boolean,
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

        return maxIndex
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
