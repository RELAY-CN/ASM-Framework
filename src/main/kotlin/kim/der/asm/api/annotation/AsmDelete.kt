/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * ASM 删除意图标记。
 *
 * 该注解用于在 ASM 类中表达“目标声明需要被移除/屏蔽”的治理意图。
 * 是否以及如何执行删除由转换器实现决定；当前模块仅提供注解定义与元数据，不保证存在对应的处理逻辑。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-06-11
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AsmDelete
