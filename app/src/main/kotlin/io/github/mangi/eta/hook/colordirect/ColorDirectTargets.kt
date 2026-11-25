package io.github.mangi.eta.hook.colordirect

import android.content.Intent
import io.github.mangi.eta.hook.support.DexKitTargets
import io.github.mangi.eta.core.ModuleConfig
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object ColorDirectTargets {
    fun findCollectIntent(targets: DexKitTargets, activityClass: Class<*>): Method? =
        targets.findMethod(
            key = "colordirect.collect-intent.v1",
            validate = { method ->
                method.declaringClass == activityClass &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(arrayOf(Intent::class.java))
            },
        ) {
            findMethod {
                matcher(collectIntentMatcher())
            }
        }

    // Activity 类名属于 Manifest 合同；处理方法会混淆，使用空输入分支的语义定位。
    fun collectIntentMatcher(): MethodMatcher = MethodMatcher.create()
        .declaredClass(ModuleConfig.COLOR_DIRECT_COLLECT_ACTIVITY_CLASS)
        .paramTypes("android.content.Intent")
        .returnType("void")
        .usingEqStrings(
            "handleIntent: no intent will finish",
            "handleIntent: no start info will finish",
        )
}
