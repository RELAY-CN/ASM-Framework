/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.util

import kim.der.asm.data.AsmInfo
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue

/**
 * 直接字符串实参匹配工具。
 *
 * 该工具只判断调用指令消费的参数值是否直接来自 `LDC String`，不追踪局部变量写回、字符串拼接、
 * 方法返回值或 bootstrap 常量。它服务于需要稳定锁定固定字符串调用点的注解式参数补丁。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
internal object DirectStringArgumentMatcher {
    private const val LDC_PREFIX = "ldc="
    private const val STRING_PREFIX = "string="

    /**
     * 解析必须存在的直接字符串过滤条件。
     *
     * `ldc=` 与 `string=` 是等价写法；调用方要求必须声明过滤值时，空参数会快速失败。
     *
     * @param args 注解声明的 `At.args`
     * @param annotationName 用于错误消息的注解与注入点名称
     * @return 需要匹配的直接字符串常量
     * @throws IllegalArgumentException 未声明、重复声明或声明了未知过滤前缀时抛出
     */
    fun parseRequiredFilter(
        args: Array<String>,
        annotationName: String,
    ): String {
        require(args.isNotEmpty()) {
            "$annotationName requires at.args entry ldc=<string> or string=<string>"
        }
        require(args.size == 1) {
            "$annotationName supports only one at.args entry ldc=<string> or string=<string>"
        }
        return parseSingleFilter(
            arg = args.single(),
            message = "$annotationName requires at.args entry ldc=<string> or string=<string>",
        )
    }

    /**
     * 解析可选的直接字符串过滤条件。
     *
     * 空参数表示不启用过滤；一旦声明参数，就只能是唯一的 `ldc=<string>` 或 `string=<string>`。
     *
     * @param args 注解声明的 `At.args`
     * @param annotationName 用于错误消息的注解与注入点名称
     * @return 需要匹配的直接字符串常量；未启用过滤时返回 `null`
     * @throws IllegalArgumentException 声明了多个参数或未知过滤前缀时抛出
     */
    fun parseOptionalFilter(
        args: Array<String>,
        annotationName: String,
    ): String? {
        if (args.isEmpty()) {
            return null
        }
        val message = "$annotationName supports at.args only as a single ldc=<string> or string=<string> direct string filter"
        require(args.size == 1) { message }
        return parseSingleFilter(args.single(), message)
    }

    /**
     * 使用 ASM 数据流分析获取每条指令执行前的来源集合。
     *
     * SourceInterpreter 只记录直接来源指令集合，足够判断调用参数是否由目标 `LDC String` 直接压栈。
     *
     * @param asmInfo 当前 ASM 注册信息
     * @param method 目标方法
     * @param annotationName 用于错误消息的注解与注入点名称
     * @return 与指令数组下标对应的分析帧
     * @throws IllegalStateException ASM 分析失败时抛出，消息保留目标方法签名方便定位
     */
    fun analyzeFrames(
        asmInfo: AsmInfo,
        method: MethodNode,
        annotationName: String,
    ): Array<Frame<SourceValue>?> =
        try {
            Analyzer(SourceInterpreter()).analyze(analysisOwner(asmInfo), method)
        } catch (e: AnalyzerException) {
            throw IllegalStateException(
                "$annotationName cannot analyze target method ${method.name}${method.desc}",
                e,
            )
        }

    /**
     * 判断调用指令的参数来源中是否包含指定的直接 `LDC String`。
     *
     * 普通方法调用、构造器调用与 `invokedynamic` 都按各自描述符里的参数区间检查；实例 receiver 不参与匹配。
     *
     * @param frame 调用指令执行前的分析帧
     * @param insn 候选调用指令
     * @param stringLiteral 需要匹配的字符串常量
     * @return 调用参数直接来自指定 `LDC String` 时返回 `true`
     */
    fun hasDirectStringArgument(
        frame: Frame<SourceValue>?,
        insn: AbstractInsnNode,
        stringLiteral: String,
    ): Boolean =
        when (insn) {
            is MethodInsnNode -> hasDirectStringArgument(frame, insn.desc, stringLiteral)
            is InvokeDynamicInsnNode -> hasDirectStringArgument(frame, insn.desc, stringLiteral)
            else -> false
        }

    /**
     * 解析单个过滤参数。
     *
     * @param arg `At.args` 中的单个参数
     * @param message 参数不合法时使用的错误消息
     * @return 需要匹配的字符串常量
     */
    private fun parseSingleFilter(
        arg: String,
        message: String,
    ): String {
        val normalized = arg.trim()
        return when {
            normalized.startsWith(LDC_PREFIX) -> normalized.substringAfter(LDC_PREFIX)
            normalized.startsWith(STRING_PREFIX) -> normalized.substringAfter(STRING_PREFIX)
            else -> throw IllegalArgumentException(message)
        }
    }

    /**
     * 解析数据流分析使用的 owner。
     *
     * 精确目标注册优先使用目标类；路径匹配注册没有固定目标类，此处退回 ASM 类名即可，因为本次来源分析
     * 不依赖 owner 的继承关系或类加载。
     *
     * @param asmInfo 当前 ASM 注册信息
     * @return JVM internal name
     */
    private fun analysisOwner(asmInfo: AsmInfo): String =
        asmInfo.targets.firstOrNull()
            ?: Type.getType(asmInfo.asmClass).internalName

    /**
     * 判断调用描述符中的参数来源是否包含指定的直接 `LDC String`。
     *
     * ASM Frame 的 operand stack 按 value entry 计数，`long` / `double` 仍只占一个 [SourceValue]。
     *
     * @param frame 调用指令执行前的分析帧
     * @param desc 调用描述符
     * @param stringLiteral 需要匹配的字符串常量
     * @return 调用参数直接来自指定 `LDC String` 时返回 `true`
     */
    private fun hasDirectStringArgument(
        frame: Frame<SourceValue>?,
        desc: String,
        stringLiteral: String,
    ): Boolean {
        if (frame == null) {
            return false
        }

        val argumentValueCount = Type.getArgumentTypes(desc).size
        val firstArgumentIndex = frame.stackSize - argumentValueCount
        if (firstArgumentIndex < 0) {
            return false
        }

        for (stackIndex in firstArgumentIndex until frame.stackSize) {
            val source = frame.getStack(stackIndex)
            if (source.insns.any { it is LdcInsnNode && it.cst == stringLiteral }) {
                return true
            }
        }
        return false
    }
}
