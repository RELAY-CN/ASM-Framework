/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Redirect 注入器。
 *
 * 查找目标方法中的匹配调用指令，并用 ASM 方法调用替换原调用点。
 * 目标签名可包含 owner，也可只包含方法名与描述符；缺少方法描述符时会被视为非法签名。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class RedirectInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val targetMethodSignature: String,
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 替换目标方法中的匹配调用点。
     *
     * @param target 目标方法
     * @return 至少替换一个调用点时返回 `true`
     * @throws IllegalArgumentException 目标调用签名无法解析时抛出
     * @throws RuntimeException 替换调用或返回值适配失败时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean {
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(targetMethodSignature)

        if (targetName == null || targetDesc == null) {
            throw IllegalArgumentException(
                "Invalid target method signature: $targetMethodSignature " +
                    "(parsed: owner=$targetOwner, name=$targetName, desc=$targetDesc)",
            )
        }

        val instructions = target.instructions
        var transformed = false
        val insns = instructions.toArray()

        for (insn in insns) {
            if (insn is MethodInsnNode && matchesTargetMethod(insn, targetOwner, targetName, targetDesc)) {
                replaceMethodCall(instructions, insn)
                transformed = true
            }
        }

        return transformed
    }

    /**
     * 解析目标方法签名。
     * 支持 owner 使用 slash 或 dot：
     * - java/lang/String.trim()Ljava/lang/String;
     * - java.lang.String.trim()Ljava/lang/String;
     * - trim()Ljava/lang/String;
     */
    private fun parseTargetMethod(signature: String): Triple<String?, String?, String?> {
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
            val owner = ownerAndName.substring(0, separatorIndex).replace('.', '/')
            val methodName = ownerAndName.substring(separatorIndex + 1)
            Triple(owner, methodName, desc)
        } else {
            Triple(null, ownerAndName, desc)
        }
    }

    private fun matchesTargetMethod(
        insn: MethodInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String,
    ): Boolean {
        if (targetOwner != null && insn.owner != targetOwner) {
            return false
        }
        if (insn.name != targetName) {
            return false
        }
        return targetDesc.isEmpty() || insn.desc == targetDesc
    }

    private fun replaceMethodCall(
        instructions: InsnList,
        originalInsn: MethodInsnNode,
    ) {
        validateHandlerSignature(originalInsn)

        val il = InsnList()
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

        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (originalReturnType == Type.VOID_TYPE && handlerReturnType != Type.VOID_TYPE) {
            il.add(InsnNode(if (handlerReturnType.size == 2) Opcodes.POP2 else Opcodes.POP))
        } else if (originalReturnType != handlerReturnType && originalReturnType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, originalReturnType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    private fun validateHandlerSignature(originalInsn: MethodInsnNode) {
        if (!Modifier.isStatic(asmMethod.modifiers)) {
            throw IllegalStateException("Redirect handler ${asmMethod.name} must be static or @JvmStatic")
        }

        val expectedParams = buildExpectedHandlerParams(originalInsn)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (expectedParams.size != actualParams.size) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} parameter count mismatch: expected ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.zip(actualParams).forEachIndexed { index, (expected, actual) ->
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect handler ${asmMethod.name} parameter #$index mismatch: expected stack type $expected, actual $actual",
                )
            }
        }

        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(originalReturnType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} return type mismatch: original $originalReturnType, handler $handlerReturnType",
            )
        }
    }

    private fun buildExpectedHandlerParams(originalInsn: MethodInsnNode): Array<Type> {
        val originalParams = Type.getArgumentTypes(originalInsn.desc).toList()
        return if (originalInsn.opcode == Opcodes.INVOKESTATIC) {
            originalParams.toTypedArray()
        } else {
            (listOf(Type.getObjectType(originalInsn.owner)) + originalParams).toTypedArray()
        }
    }

    private fun isHandlerParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) return true
        if (expected.sort == Type.OBJECT || expected.sort == Type.ARRAY) {
            return actual.sort == Type.OBJECT && actual.internalName == "java/lang/Object"
        }
        return false
    }

    private fun isReturnCompatible(
        original: Type,
        handler: Type,
    ): Boolean {
        if (original == Type.VOID_TYPE) return true
        if (handler == Type.VOID_TYPE) return false
        if (original == handler) return true
        return (original.sort == Type.OBJECT || original.sort == Type.ARRAY) && handler.sort >= Type.ARRAY
    }
}
