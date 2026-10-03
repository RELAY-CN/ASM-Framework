/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm.fixture;

import kim.der.asm.api.annotation.AddField;
import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.Copy;
import kim.der.asm.api.annotation.InjectionPoint;
import kim.der.asm.api.annotation.Overwrite;
import kim.der.asm.api.annotation.Shadow;

/**
 * 字段引用回归测试的 Java 字节码来源。
 *
 * <p>三个复制路径有意保留各自的方法体，使 javac 在每个 Mixin 内生成真实字段指令；
 * handler 不通过反射或公共 helper 绕过待验证的 owner/name 重写。
 */
public final class AddFieldReferenceFixtures {
    private AddFieldReferenceFixtures() { }

    /** 同名的外部字段不属于 Mixin，复制时必须保留它的 owner。 */
    public static class External {
        public static int value;
    }

    /** 覆盖实例和静态方法，验证 AddField 与 Shadow 字段可共同直接读写。 */
    @AsmMixin("fieldrefs/Target")
    public static class JavaOverwriteMixin {
        @AddField private int value = 7;
        @AddField(field = "score") private long mixinScore = 9L;
        @AddField(field = "label") private static String mixinLabel = "mixin";
        @AddField(field = "total") private static long mixinTotal = 88L;
        @AddField(field = "kept") private int duplicate;
        @Shadow(method = "existing") private int existingAlias;
        @Shadow(method = "shadow_legacy") private int prefixed;

        /** 写入新增字段、别名字段和已有字段，并保留外部字段访问。 */
        @Overwrite(method = "run(I)I")
        public int run(int input) {
            value = input;
            mixinScore = value + 1L;
            mixinLabel = "target";
            existingAlias = value + 2;
            prefixed = existingAlias + 1;
            duplicate = prefixed + 1;
            External.value = value + 99;
            return value;
        }

        /** 静态目标没有 this，GETSTATIC/PUTSTATIC 也必须映射到目标声明。 */
        @Overwrite(method = "runStatic(I)J")
        public static long runStatic(int input) {
            mixinTotal = input;
            return ++mixinTotal;
        }
    }

    /** 内联 handler 只操作字段，目标方法仍负责返回结果。 */
    @AsmMixin("fieldrefs/Target")
    public static class JavaInlineMixin {
        @AddField private int value = 7;
        @AddField(field = "score") private long mixinScore = 9L;
        @AddField(field = "label") private static String mixinLabel = "mixin";
        @AddField(field = "total") private static long mixinTotal = 88L;
        @AddField(field = "kept") private int duplicate;
        @Shadow(method = "existing") private int existingAlias;
        @Shadow(method = "shadow_legacy") private int prefixed;

        /** 不附加 Shadow，直接访问 AddField 声明所对应的目标字段。 */
        @AsmInject(method = "run(I)I", target = InjectionPoint.HEAD, inline = true)
        public void inject(int input) {
            value = input;
            mixinScore = value + 1L;
            mixinLabel = "target";
            existingAlias = value + 2;
            prefixed = existingAlias + 1;
            duplicate = prefixed + 1;
            External.value = value + 99;
        }

        /** 静态内联字段运算不应写入原 Mixin 的静态状态。 */
        @AsmInject(method = "runStatic(I)J", target = InjectionPoint.HEAD, inline = true)
        public static void injectStatic(int input) {
            mixinTotal = input;
            ++mixinTotal;
        }
    }

    /** 复制的新方法必须使用与覆盖、内联相同的字段映射。 */
    @AsmMixin("fieldrefs/Target")
    public static class JavaCopyMixin {
        @AddField private int value = 7;
        @AddField(field = "score") private long mixinScore = 9L;
        @AddField(field = "label") private static String mixinLabel = "mixin";
        @AddField(field = "total") private static long mixinTotal = 88L;
        @AddField(field = "kept") private int duplicate;
        @Shadow(method = "existing") private int existingAlias;
        @Shadow(method = "shadow_legacy") private int prefixed;

        /** 复制后读写的 receiver 是目标对象，不是 fixture 实例。 */
        @Copy
        public int copied(int input) {
            value = input;
            mixinScore = value + 1L;
            mixinLabel = "target";
            existingAlias = value + 2;
            prefixed = existingAlias + 1;
            duplicate = prefixed + 1;
            External.value = value + 99;
            return value;
        }

        /** 保留 static 契约，并把静态字段别名解析到目标类。 */
        @Copy
        public static long copiedStatic(int input) {
            mixinTotal = input;
            return ++mixinTotal;
        }
    }

    /** AddField 的声明名优先于同时存在的 Shadow 提示，避免声明和引用分歧。 */
    @AsmMixin("fieldrefs/Target")
    public static class CombinedAnnotationsMixin {
        @AddField(field = "value") @Shadow(method = "unused") private int alias;

        /** 目标类只会创建 value，因此写指令也必须指向 value。 */
        @Overwrite(method = "run(I)I")
        public int run(int input) { alias = input; return alias; }
    }

    /** 普通回调仍在 Mixin 实例上执行；新增目标字段不能改变 this 语义。 */
    @AsmMixin("fieldrefs/Target")
    public static class CallbackMixin {
        @AddField private int value = 7;
        public static int observed;

        /** 记录 Mixin 内部赋值，供测试与目标实例字段对比。 */
        @AsmInject(method = "run(I)I", target = InjectionPoint.HEAD)
        public void inject(int input) { value = input; observed = value; }
    }

    /** 字段初始化属于 Mixin 构造器或类初始化器，不随声明复制。 */
    @AsmMixin("fieldrefs/Target")
    public static class DeclarationMixin {
        @AddField public int value = 7;
        @AddField public static String label = "mixin";
    }
}
