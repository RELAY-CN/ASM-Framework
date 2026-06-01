/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api

import org.objectweb.asm.tree.ClassNode

/**
 * ClassNode 转换接口。
 *
 * 用于扩展基于 ASM Tree API 的类节点改写逻辑。实现方应直接修改传入的 [ClassNode]，
 * 不需要返回新的节点；调用方负责后续写回字节码。
 *
 * 该接口不约束线程安全。若实现类持有可变状态，应由实现类或调用方保证同一实例不会被并发复用。
 *
 * @author Dr (dr@der.kim)
 * @date 2026-06-01
 */
fun interface Transformer {
    /**
     * 转换类节点。
     *
     * 该入口只暴露 [ClassNode]，不再提供旧版 Redirection manager/listener 的目标方法描述列表。
     * 需要替换调用、监听调用或改写表达式时，应使用 `@Redirect`、`@WrapOperation`、
     * `@WrapWithCondition`、`@ModifyExpressionValue` 或其他注解式 Mixin API。
     *
     * @param classNode 待改写的类节点
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    fun transform(classNode: ClassNode)
}
