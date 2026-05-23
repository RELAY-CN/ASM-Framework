/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api

import kim.der.asm.data.MethodTypeInfoValue
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
 * @date 2025-11-24
 */
fun interface Transformer {
    /**
     * 转换类节点。
     *
     * 该便捷入口不会提供重定向/监听目标描述列表，适合只依赖 [ClassNode] 本身的改写。
     *
     * @param classNode 待改写的类节点
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun transform(classNode: ClassNode) {
        transform(classNode, null)
    }

    /**
     * 转换类节点，并可接收外部收集的目标方法描述。
     *
     * [methodTypeInfoValueList] 由调用方按需传入，通常用于重定向或监听器场景。
     * 实现方可以读取或追加元素，但应避免依赖调用方未声明的列表所有权。
     *
     * @param classNode 待改写的类节点
     * @param methodTypeInfoValueList 目标方法描述列表；为 `null` 表示本次转换不提供该上下文
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun transform(
        classNode: ClassNode,
        methodTypeInfoValueList: ArrayList<MethodTypeInfoValue>?,
    )
}
