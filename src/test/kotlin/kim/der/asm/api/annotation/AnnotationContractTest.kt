/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.lang.annotation.ElementType
import java.lang.annotation.RetentionPolicy
import java.util.stream.Stream

@DisplayName("公开注解 API 契约")
class AnnotationContractTest {
    @Nested
    @DisplayName("元数据场景")
    inner class MetadataScenarios {
        @ParameterizedTest(name = "{0} 应在运行期可见")
        @MethodSource("kim.der.asm.api.annotation.AnnotationContractTest#publicAnnotationContracts")
        @DisplayName("所有公开注解都应保留运行期反射契约")
        fun publicAnnotationsKeepRuntimeRetention(contract: AnnotationContract) {
            // Given / When
            val retention = contract.type.getAnnotation(java.lang.annotation.Retention::class.java)

            // Then
            assertThat(retention)
                .`as`("${contract.type.simpleName} 必须运行期可见，否则注册器与转换器无法通过反射读取配置")
                .isNotNull
            assertThat(retention.value)
                .`as`("${contract.type.simpleName} 的保留策略应保持 RUNTIME")
                .isEqualTo(RetentionPolicy.RUNTIME)
        }

        @ParameterizedTest(name = "{0} 应暴露合法 JVM 目标")
        @MethodSource("kim.der.asm.api.annotation.AnnotationContractTest#publicAnnotationContracts")
        @DisplayName("所有公开注解都应保持声明位置契约")
        fun publicAnnotationsKeepExpectedTargets(contract: AnnotationContract) {
            // Given / When
            val target = contract.type.getAnnotation(java.lang.annotation.Target::class.java)

            // Then
            if (contract.expectedTargets.isEmpty()) {
                assertThat(target)
                    .`as`("${contract.type.simpleName} 是注解参数值对象，不应限制为类、方法或字段声明")
                    .isNull()
            } else {
                assertThat(target)
                    .`as`("${contract.type.simpleName} 必须声明可用位置，避免错误标注到无效 JVM 元素")
                    .isNotNull
                assertThat(target.value.toSet())
                    .`as`("${contract.type.simpleName} 的 JVM 可用位置应与 Kotlin @Target 保持一致")
                    .containsExactlyInAnyOrderElementsOf(contract.expectedTargets)
            }
        }

        @ParameterizedTest(name = "{0} 默认参数应稳定")
        @MethodSource("kim.der.asm.api.annotation.AnnotationContractTest#publicAnnotationDefaultContracts")
        @DisplayName("所有公开注解都应保持构造参数默认值契约")
        fun publicAnnotationsKeepDefaultParameterContracts(contract: AnnotationDefaultContract) {
            // Given
            val annotationType = contract.annotation.annotationClass.java

            // When
            val properties = annotationType.declaredMethods.map { it.name }.toSet()

            // Then
            assertThat(properties)
                .`as`("${annotationType.simpleName} 的公开注解参数集合应保持稳定")
                .containsExactlyInAnyOrderElementsOf(contract.expectedProperties.keys)
            contract.expectedProperties.forEach { (property, expected) ->
                val actual = annotationType.getDeclaredMethod(property).invoke(contract.annotation)

                assertAnnotationProperty(annotationType.simpleName, property, actual, expected)
            }
        }

        @Test
        @DisplayName("@AsmDelete 配置应在类、方法与字段上运行期可见")
        fun asmDeleteConfigurationIsRuntimeVisibleOnSupportedTargets() {
            // Given
            val fixtureClass = DeleteIntentFixture::class.java
            val method = fixtureClass.getDeclaredMethod("deprecatedEndpoint")

            // When
            val classAnnotation = fixtureClass.getAnnotation(AsmDelete::class.java)
            val methodAnnotation = method.getAnnotation(AsmDelete::class.java)
            val field = fixtureClass.getDeclaredField("legacyField")
            val fieldAnnotation = field.getAnnotation(AsmDelete::class.java)

            // Then
            assertThat(classAnnotation)
                .`as`("@AsmDelete 标注类时应保留运行期配置，以便转换器明确拒绝整类删除")
                .isNotNull
            assertThat(methodAnnotation)
                .`as`("@AsmDelete 标注方法时应保留运行期删除配置")
                .isNotNull
            assertThat(fieldAnnotation)
                .`as`("@AsmDelete 标注字段时应作为运行期删除配置保留下来")
                .isNotNull
        }
    }

    @Nested
    @DisplayName("定位值对象场景")
    inner class LocatorValueScenarios {
        @Test
        @DisplayName("@At 默认值应表达未指定具体指令定位")
        fun atDefaultsRepresentUnspecifiedInstructionLocator() {
            // Given / When
            val at = At()

            // Then
            assertThat(at.value)
                .`as`("@At 默认注入点应为 HEAD，表示未声明具体指令匹配目标")
                .isEqualTo(InjectionPoint.HEAD)
            assertThat(at.target)
                .`as`("@At 默认目标签名应为空，避免误匹配某个具体调用点")
                .isEmpty()
            assertThat(at.shift)
                .`as`("@At 默认偏移应在匹配点之前插入")
                .isEqualTo(Shift.BEFORE)
            assertThat(at.by)
                .`as`("@At 默认真实指令偏移应为 0")
                .isZero()
            assertThat(at.args)
                .`as`("@At 默认附加过滤参数应为空")
                .isEmpty()
        }

        @Test
        @DisplayName("@At 应保留复杂目标与国际化过滤参数")
        fun atPreservesComplexTargetAndInternationalizedArguments() {
            // Given / When
            val at =
                At(
                    value = InjectionPoint.INVOKE,
                    target = "kim/der/example/玩家服务.join(Ljava/lang/String;)V",
                    shift = Shift.AFTER,
                    by = -2,
                    args = arrayOf("ldc=房间=CN-一号", "name=玩家 名"),
                )

            // Then
            assertThat(at.value)
                .`as`("@At 应保留调用点类型")
                .isEqualTo(InjectionPoint.INVOKE)
            assertThat(at.target)
                .`as`("@At 应原样保留包含国际化字符的 JVM 目标文本")
                .isEqualTo("kim/der/example/玩家服务.join(Ljava/lang/String;)V")
            assertThat(at.shift)
                .`as`("@At 应保留显式 AFTER 偏移策略")
                .isEqualTo(Shift.AFTER)
            assertThat(at.by)
                .`as`("@At 应允许负向真实指令偏移")
                .isEqualTo(-2)
            assertThat(at.args)
                .`as`("@At 应原样保留包含等号、空格和中文的过滤参数")
                .containsExactly("ldc=房间=CN-一号", "name=玩家 名")
        }

        @Test
        @DisplayName("@Slice 默认值应表达完整方法范围")
        fun sliceDefaultsRepresentWholeMethodRange() {
            // Given / When
            val slice = Slice()

            // Then
            assertThat(slice.from)
                .`as`("@Slice 默认起始边界应为未声明 @At")
                .isEqualTo(At())
            assertThat(slice.to)
                .`as`("@Slice 默认结束边界应为未声明 @At")
                .isEqualTo(At())
            assertThat(slice.id)
                .`as`("@Slice 默认标识应为空，避免制造第二事实源")
                .isEmpty()
        }

        @Test
        @DisplayName("@Slice 应保留起止边界与国际化标识")
        fun slicePreservesBoundariesAndInternationalizedId() {
            // Given
            val from = At(value = InjectionPoint.INVOKE, target = "start()V")
            val to = At(value = InjectionPoint.CONSTANT, target = "阶段=结算")

            // When
            val slice = Slice(from = from, to = to, id = "跨天-阶段二")

            // Then
            assertThat(slice.from)
                .`as`("@Slice 应保留起始边界定位")
                .isEqualTo(from)
            assertThat(slice.to)
                .`as`("@Slice 应保留结束边界定位")
                .isEqualTo(to)
            assertThat(slice.id)
                .`as`("@Slice 应原样保留诊断用国际化标识")
                .isEqualTo("跨天-阶段二")
        }
    }

    @Nested
    @DisplayName("局部变量捕获场景")
    inner class LocalCaptureScenarios {
        @Test
        @DisplayName("@Local 默认值应表达未指定名称和槽位")
        fun localDefaultsRepresentUnspecifiedCaptureFilter() {
            // Given
            val method = LocalCaptureFixture::class.java.getDeclaredMethod("captureAny", String::class.java)

            // When
            val local = method.firstParameterAnnotation<Local>()

            // Then
            assertThat(local.value)
                .`as`("@Local 默认 value 应为空，表示不按简写名称过滤")
                .isEmpty()
            assertThat(local.name)
                .`as`("@Local 默认 name 应为空，表示不按 LocalVariableTable 名称过滤")
                .isEmpty()
            assertThat(local.index)
                .`as`("@Local 默认 index 应为 -1，表示不按 JVM 槽位过滤")
                .isEqualTo(-1)
        }

        @Test
        @DisplayName("@Local 应在参数上保留名称与槽位过滤")
        fun localPreservesNameAndIndexOnParameter() {
            // Given
            val method = LocalCaptureFixture::class.java.getDeclaredMethod("captureNamed", String::class.java)

            // When
            val local = method.firstParameterAnnotation<Local>()

            // Then
            assertThat(local.value)
                .`as`("@Local value 简写别名应原样保留")
                .isEqualTo("玩家")
            assertThat(local.name)
                .`as`("@Local name 应原样保留国际化变量名")
                .isEqualTo("玩家 名")
            assertThat(local.index)
                .`as`("@Local index 应保留目标 JVM 局部变量槽位")
                .isEqualTo(3)
        }
    }

    companion object {
        private val classTarget = setOf(ElementType.TYPE)
        private val functionTarget = setOf(ElementType.METHOD)
        private val fieldTarget = setOf(ElementType.FIELD)
        private val parameterTarget = setOf(ElementType.PARAMETER)
        private val classFunctionFieldTarget = setOf(ElementType.TYPE, ElementType.METHOD, ElementType.FIELD)
        private val fieldAndFunctionTarget = setOf(ElementType.FIELD, ElementType.METHOD)

        @JvmStatic
        fun publicAnnotationContracts(): Stream<Arguments> =
            Stream.of(
                annotationContract(AsmDelete::class.java, classFunctionFieldTarget),
                annotationContract(AsmInject::class.java, functionTarget),
                annotationContract(At::class.java, emptySet()),
                annotationContract(Slice::class.java, emptySet()),
                annotationContract(Local::class.java, parameterTarget),
                annotationContract(AsmMixin::class.java, classTarget),
                annotationContract(Group::class.java, functionTarget),
                annotationContract(AddInterface::class.java, classTarget),
                annotationContract(RemoveInterface::class.java, classTarget),
                annotationContract(ReplaceAllMethods::class.java, classTarget),
                annotationContract(RedirectAllMethods::class.java, classTarget),
                annotationContract(Overwrite::class.java, functionTarget),
                annotationContract(Copy::class.java, functionTarget),
                annotationContract(Unique::class.java, fieldAndFunctionTarget),
                annotationContract(ModifyArg::class.java, functionTarget),
                annotationContract(ModifyArgs::class.java, functionTarget),
                annotationContract(ModifyReceiver::class.java, functionTarget),
                annotationContract(WrapOperation::class.java, functionTarget),
                annotationContract(WrapMethod::class.java, functionTarget),
                annotationContract(WrapWithCondition::class.java, functionTarget),
                annotationContract(ModifyExpressionValue::class.java, functionTarget),
                annotationContract(ModifyVariable::class.java, functionTarget),
                annotationContract(ModifyReturnValue::class.java, functionTarget),
                annotationContract(ModifyConstant::class.java, functionTarget),
                annotationContract(Redirect::class.java, functionTarget),
                annotationContract(Shadow::class.java, fieldAndFunctionTarget),
                annotationContract(Accessor::class.java, functionTarget),
                annotationContract(Invoker::class.java, functionTarget),
                annotationContract(Mutable::class.java, fieldAndFunctionTarget),
                annotationContract(Final::class.java, fieldTarget),
                annotationContract(AddField::class.java, fieldTarget),
                annotationContract(RemoveField::class.java, fieldAndFunctionTarget),
                annotationContract(RemoveMethod::class.java, functionTarget),
                annotationContract(RemoveSynchronized::class.java, functionTarget),
            )

        @JvmStatic
        fun publicAnnotationDefaultContracts(): Stream<Arguments> =
            Stream.of(
                defaultContract(AsmDelete(), linkedMapOf("value" to "")),
                defaultContract(
                    AsmInject(),
                    linkedMapOf(
                        "method" to "",
                        "target" to InjectionPoint.HEAD,
                        "cancellable" to false,
                        "require" to 0,
                        "at" to At(),
                        "ordinal" to -1,
                        "slice" to Slice(),
                        "allow" to -1,
                        "expect" to 1,
                        "inline" to false,
                    ),
                ),
                defaultContract(
                    At(),
                    linkedMapOf(
                        "value" to InjectionPoint.HEAD,
                        "target" to "",
                        "shift" to Shift.BEFORE,
                        "by" to 0,
                        "args" to emptyArray<String>(),
                    ),
                ),
                defaultContract(
                    Slice(),
                    linkedMapOf(
                        "from" to At(),
                        "to" to At(),
                        "id" to "",
                    ),
                ),
                defaultContract(
                    Local(),
                    linkedMapOf(
                        "value" to "",
                        "name" to "",
                        "index" to -1,
                    ),
                ),
                defaultContract(
                    AsmMixin(),
                    linkedMapOf(
                        "value" to "",
                        "remap" to false,
                        "targets" to emptyArray<String>(),
                        "priority" to 1000,
                    ),
                ),
                defaultContract(
                    Group(name = "fallback"),
                    linkedMapOf(
                        "name" to "fallback",
                        "min" to 1,
                        "max" to Int.MAX_VALUE,
                        "expect" to -1,
                    ),
                ),
                defaultContract(
                    AddInterface(),
                    linkedMapOf(
                        "value" to "",
                        "interfaces" to emptyArray<String>(),
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    RemoveInterface(),
                    linkedMapOf(
                        "value" to "",
                        "interfaces" to emptyArray<String>(),
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    ReplaceAllMethods(),
                    linkedMapOf(
                        "removeSync" to false,
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    RedirectAllMethods(),
                    linkedMapOf("remap" to false),
                ),
                defaultContract(
                    Overwrite(),
                    linkedMapOf(
                        "method" to "",
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    Copy(),
                    linkedMapOf(
                        "method" to "",
                        "remap" to false,
                    ),
                ),
                defaultContract(Unique(), emptyMap()),
                defaultContract(
                    ModifyArg(),
                    linkedMapOf(
                        "method" to "",
                        "index" to -1,
                        "at" to At(),
                        "ordinal" to -1,
                        "slice" to Slice(),
                        "require" to 0,
                        "expect" to 1,
                        "allow" to -1,
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    ModifyArgs(),
                    linkedMapOf(
                        "method" to "",
                        "at" to At(value = InjectionPoint.INVOKE),
                        "ordinal" to -1,
                        "slice" to Slice(),
                        "require" to 0,
                        "expect" to 1,
                        "allow" to -1,
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    ModifyReceiver(),
                    linkedMapOf(
                        "method" to "",
                        "at" to At(value = InjectionPoint.INVOKE),
                        "ordinal" to -1,
                        "slice" to Slice(),
                        "require" to 0,
                        "expect" to 1,
                        "allow" to -1,
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    WrapOperation(),
                    linkedMapOf(
                        "method" to "",
                        "at" to At(value = InjectionPoint.INVOKE),
                        "ordinal" to -1,
                        "slice" to Slice(),
                        "require" to 0,
                        "expect" to 1,
                        "allow" to -1,
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    WrapMethod(),
                    linkedMapOf(
                        "method" to "",
                        "require" to 0,
                        "expect" to 1,
                        "allow" to -1,
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    WrapWithCondition(),
                    linkedMapOf(
                        "method" to "",
                        "at" to At(value = InjectionPoint.INVOKE),
                        "ordinal" to -1,
                        "slice" to Slice(),
                        "require" to 0,
                        "expect" to 1,
                        "allow" to -1,
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    ModifyExpressionValue(),
                    linkedMapOf(
                        "method" to "",
                        "at" to At(value = InjectionPoint.INVOKE),
                        "ordinal" to -1,
                        "slice" to Slice(),
                        "require" to 0,
                        "expect" to 1,
                        "allow" to -1,
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    ModifyVariable(),
                    linkedMapOf(
                        "method" to "",
                        "at" to At(value = InjectionPoint.HEAD),
                        "index" to -1,
                        "name" to emptyArray<String>(),
                        "ordinal" to -1,
                        "slice" to Slice(),
                        "require" to 0,
                        "expect" to 1,
                        "allow" to -1,
                        "remap" to false,
                        "argsOnly" to false,
                    ),
                ),
                defaultContract(
                    ModifyReturnValue(),
                    linkedMapOf(
                        "method" to "",
                        "at" to At(),
                        "ordinal" to -1,
                        "require" to 0,
                        "expect" to 1,
                        "allow" to -1,
                        "remap" to false,
                        "slice" to Slice(),
                    ),
                ),
                defaultContract(
                    ModifyConstant(),
                    linkedMapOf(
                        "method" to "",
                        "constant" to "",
                        "ordinal" to -1,
                        "slice" to Slice(),
                        "require" to 0,
                        "expect" to 1,
                        "allow" to -1,
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    Redirect(),
                    linkedMapOf(
                        "method" to "",
                        "target" to "",
                        "at" to At(),
                        "ordinal" to -1,
                        "slice" to Slice(),
                        "require" to 0,
                        "expect" to 1,
                        "allow" to -1,
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    Shadow(),
                    linkedMapOf(
                        "method" to "",
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    Accessor(),
                    linkedMapOf(
                        "value" to "",
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    Invoker(),
                    linkedMapOf(
                        "value" to "",
                        "remap" to false,
                    ),
                ),
                defaultContract(Mutable(), emptyMap()),
                defaultContract(Final(), emptyMap()),
                defaultContract(
                    AddField(),
                    linkedMapOf(
                        "field" to "",
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    RemoveField(),
                    linkedMapOf(
                        "field" to "",
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    RemoveMethod(),
                    linkedMapOf(
                        "method" to "",
                        "remap" to false,
                    ),
                ),
                defaultContract(
                    RemoveSynchronized(),
                    linkedMapOf(
                        "method" to "",
                        "remap" to false,
                    ),
                ),
            )

        private fun annotationContract(
            type: Class<*>,
            expectedTargets: Set<ElementType>,
        ): Arguments = Arguments.of(AnnotationContract(type, expectedTargets))

        private fun defaultContract(
            annotation: Annotation,
            expectedProperties: Map<String, Any>,
        ): Arguments = Arguments.of(AnnotationDefaultContract(annotation, expectedProperties))
    }

    data class AnnotationContract(
        val type: Class<*>,
        val expectedTargets: Set<ElementType>,
    ) {
        override fun toString(): String = "@${type.simpleName}"
    }

    data class AnnotationDefaultContract(
        val annotation: Annotation,
        val expectedProperties: Map<String, Any>,
    ) {
        override fun toString(): String = "@${annotation.annotationClass.java.simpleName}"
    }

    @AsmDelete
    private class DeleteIntentFixture {
        @AsmDelete
        @Suppress("unused")
        fun deprecatedEndpoint() {
        }

        @AsmDelete("legacyField")
        @JvmField
        @Suppress("unused")
        val legacyField: String? = null
    }

    private object LocalCaptureFixture {
        @Suppress("unused", "UNUSED_PARAMETER")
        fun captureAny(@Local value: String) {
        }

        @Suppress("unused", "UNUSED_PARAMETER")
        fun captureNamed(@Local("玩家", name = "玩家 名", index = 3) value: String) {
        }
    }
}

private inline fun <reified T : Annotation> java.lang.reflect.Method.firstParameterAnnotation(): T =
    parameterAnnotations.first().filterIsInstance<T>().single()

private fun assertAnnotationProperty(
    annotationName: String,
    property: String,
    actual: Any?,
    expected: Any,
) {
    if (expected is Array<*>) {
        assertThat(actual as Array<*>)
            .`as`("$annotationName.$property 应保持公开默认数组契约")
            .containsExactly(*expected)
    } else {
        assertThat(actual)
            .`as`("$annotationName.$property 应保持公开默认值契约")
            .isEqualTo(expected)
    }
}
