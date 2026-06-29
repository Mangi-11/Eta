package fuck.andes.hook.system

import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleConfig
import fuck.andes.core.ModuleLogger

import io.github.libxposed.api.XposedModule

internal object SystemServerHooks {

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        ContextualSearchHooks.install(module, logger, classLoader)
        AssistantManager.install(module, logger, classLoader)
        HotwordSelfHealHooks.install(module, logger, classLoader)
        PowerHooks.install(module, logger, classLoader)
    }
}
