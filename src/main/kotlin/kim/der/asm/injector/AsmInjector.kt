/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector

import org.objectweb.asm.tree.MethodNode

/**
 * ASM 注入器接口
 * 用于将 ASM 方法注入到目标方法中
 *
 * @author Dr (dr@der.kim)
 */
interface AsmInjector {
    /**
     * 注入到目标方法
     *
     * @param target 目标方法
     * @return true 如果成功注入
     */
    fun inject(target: MethodNode): Boolean
}
