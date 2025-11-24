/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Mutable

/**
 * Accessor ASM: 为所有字段生成访问器方法
 *
 * Accessor 会在目标类中生成 getter/setter 方法，允许外部代码访问私有字段。
 *
 * 使用场景：
 * 1. 实例字段访问器：为实例字段生成 getter/setter
 * 2. 静态字段访问器：为静态字段生成 getter/setter（需要 @JvmStatic）
 * 3. final 字段访问器：为 final 字段生成访问器（需要 @Mutable 移除 final 修饰符）
 *
 * 与 Shadow 结合使用：
 * - Accessor 为外部代码提供访问接口
 * - Shadow 在 Mixin 类内部提供字段引用
 * - 可以在同一个 Mixin 中同时使用两者：
 *   ```kotlin
 *   @AsmMixin("Test")
 *   class MyMixin {
 *       @Shadow(prefix = "")
 *       private val dynamicString: String? = null  // 在注入方法中使用
 *       
 *       @Accessor("dynamicString")
 *       fun getDynamicString(): String { ... }     // 供外部代码使用
 *   }
 *   ```
 *
 * 示例：
 * ```kotlin
 * // 在目标类 Test 中会生成：
 * public String getDynamicString() {
 *     return this.dynamicString;
 * }
 *
 * public void setDynamicString(String value) {
 *     this.dynamicString = value;
 * }
 *
 * public static String getStaticString() {
 *     return staticString;
 * }
 *
 * public static void setStaticString(String value) {
 *     staticString = value;
 * }
 * ```
 */
@AsmMixin("Test")
object AccessorMixin {
    /**
     * 实例字段访问器示例
     *
     * 生成的代码：
     * ```java
     * public String getDynamicString() {
     *     return this.dynamicString;
     * }
     *
     * public void setDynamicString(String value) {
     *     this.dynamicString = value;
     * }
     * ```
     */
    @Accessor("dynamicString")
    fun getDynamicString(): String {
        throw UnsupportedOperationException("Accessor should not be called directly")
    }

    @Accessor("dynamicString")
    fun setDynamicString(value: String) {
        throw UnsupportedOperationException("Accessor should not be called directly")
    }

    /**
     * 静态字段访问器示例
     *
     * 注意：静态字段的访问器必须是静态方法，需要使用 @JvmStatic
     *
     * 生成的代码：
     * ```java
     * public static String getStaticString() {
     *     return staticString;
     * }
     *
     * public static void setStaticString(String value) {
     *     staticString = value;
     * }
     * ```
     */
    @Accessor("staticString")
    @JvmStatic
    fun getStaticString(): String {
        throw UnsupportedOperationException("Accessor should not be called directly")
    }

    @Accessor("staticString")
    @JvmStatic
    fun setStaticString(value: String) {
        throw UnsupportedOperationException("Accessor should not be called directly")
    }

    /**
     * final 字段访问器示例
     *
     * 注意：
     * 1. 需要 @Mutable 来移除 final 修饰符
     * 2. 静态 final 字段的访问器必须是静态方法，需要使用 @JvmStatic
     *
     * 生成的代码：
     * ```java
     * // @Mutable 会移除 final 修饰符
     * public static String getStaticFinalString() {
     *     return staticFinalString;
     * }
     *
     * public static void setStaticFinalString(String value) {
     *     staticFinalString = value;  // 现在可以修改了
     * }
     * ```
     */
    @Accessor("staticFinalString")
    @Mutable
    @JvmStatic
    fun getStaticFinalString(): String {
        throw UnsupportedOperationException("Accessor should not be called directly")
    }

    @Accessor("staticFinalString")
    @Mutable
    @JvmStatic
    fun setStaticFinalString(value: String) {
        throw UnsupportedOperationException("Accessor should not be called directly")
    }
}
