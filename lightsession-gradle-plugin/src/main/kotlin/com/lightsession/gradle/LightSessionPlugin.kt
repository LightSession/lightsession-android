package com.lightsession.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Installs the network interceptor without the app writing a line of Kotlin.
 *
 * ## Why this exists rather than a runtime hook
 *
 * OkHttp has no global registry: `javap` on `OkHttpClient` shows a `Companion` and two default
 * lists and nothing else static — no factory to replace, no service loader, no way to reach a
 * client the app already built. The one framework where this *is* solvable at runtime is React
 * Native, because it centralises creation in `OkHttpClientProvider`, and a competitor's Android
 * SDK does exactly that from a `ContentProvider`. A native app has no such single point: every
 * module builds its own client, in its own object graph, reachable from nothing we can walk.
 *
 * So the interception is inserted where the call sites are visible, which is the bytecode. This is
 * a build-time transform over `OkHttpClient.Builder.build()`.
 *
 * ## What it does to the bytecode
 *
 * Before every `build()` on an `OkHttpClient$Builder`, it emits
 *
 * ```
 * NEW  com/lightsession/network/LightSessionInterceptor
 * DUP
 * INVOKESPECIAL <init>()V
 * INVOKEVIRTUAL OkHttpClient$Builder.addInterceptor(Interceptor) : OkHttpClient$Builder
 * ```
 *
 * `addInterceptor` returns the builder, so the stack is exactly as `build()` found it and no
 * frames move — which is why this needs no stack-map recomputation beyond what ASM does anyway.
 *
 * ## Scope, and the trade in it
 *
 * [InstrumentationScope.ALL] covers the app's own classes *and* its dependencies, so a client
 * built inside a third-party SDK is instrumented too. That is the half a hand-written line can
 * never reach, and it is also the half worth stating plainly: an app that adopts this records
 * traffic it does not itself write, including whatever a vendored SDK talks to. `PROJECT` is the
 * narrower choice and is what a cautious first release should default to.
 */
public abstract class LightSessionPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val components = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: error(
                "com.lightsession.instrumentation must be applied to an Android module, after " +
                    "the Android plugin",
            )

        components.onVariants { variant ->
            variant.instrumentation.transformClassesWith(
                OkHttpClassVisitorFactory::class.java,
                InstrumentationScope.PROJECT,
            ) {}
            // The transform inserts a call whose result is the same type the stack already held,
            // so no frame changes — but ASM is asked to keep frames coherent anyway, because a
            // wrong stack map is a `VerifyError` at install time rather than a test failure.
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COPY_FRAMES,
            )
        }
    }
}

internal abstract class OkHttpClassVisitorFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = OkHttpBuilderVisitor(nextClassVisitor)

    /**
     * Every class is offered, and the visitor decides per call site.
     *
     * Filtering by class name here would be guesswork: a client can be built in a DI module, an
     * Application, a repository or a lambda in any of them.
     */
    override fun isInstrumentable(classData: ClassData): Boolean = true
}

private const val BUILDER = "okhttp3/OkHttpClient\$Builder"
private const val INTERCEPTOR = "com/lightsession/network/LightSessionInterceptor"

private class OkHttpBuilderVisitor(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
        return object : MethodVisitor(Opcodes.ASM9, delegate) {
            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                methodName: String,
                methodDescriptor: String,
                isInterface: Boolean,
            ) {
                val isBuild = opcode == Opcodes.INVOKEVIRTUAL &&
                    owner == BUILDER &&
                    methodName == "build" &&
                    methodDescriptor == "()Lokhttp3/OkHttpClient;"
                if (isBuild) {
                    super.visitTypeInsn(Opcodes.NEW, INTERCEPTOR)
                    super.visitInsn(Opcodes.DUP)
                    super.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, INTERCEPTOR, "<init>", "()V", false,
                    )
                    super.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        BUILDER,
                        "addInterceptor",
                        "(Lokhttp3/Interceptor;)L$BUILDER;",
                        false,
                    )
                }
                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface)
            }
        }
    }
}
