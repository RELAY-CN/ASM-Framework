/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector

import org.objectweb.asm.tree.MethodNode

/**
 * ASM 注入器接口。
 *
 * 注入器负责把一个 ASM 方法对应的改写逻辑应用到目标 [MethodNode]。
 * 实现方通常会直接修改 [MethodNode.instructions]、局部变量表或栈帧相关元数据。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
interface AsmInjector {
    /**
     * 注入到目标方法。
     *
     * @param target 目标方法
     * @return 如果成功注入并改变目标方法则返回 `true`
     * @throws RuntimeException 注入过程中遇到不合法签名、参数映射或字节码结构时可抛出运行时异常
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun inject(target: MethodNode): Boolean
}
