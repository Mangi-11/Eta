package io.github.mangi.eta.agent.virtualdisplay

/** 调用方在同一状态锁内使用；创建、移除与子进程退出都必须携带对应实例的身份。 */
internal class VirtualDisplaySessionState {
    class Token internal constructor()

    data class Active(val token: Token, val displayId: Int)

    private var token: Token? = null
    var active: Active? = null
        private set

    fun begin(): Token {
        check(token == null)
        return Token().also { token = it }
    }

    fun activate(expected: Token, displayId: Int): Boolean {
        require(displayId > 0)
        if (token !== expected) return false
        active = Active(expected, displayId)
        return true
    }

    fun isCurrent(expected: Token): Boolean = token === expected

    fun invalidate(expected: Token? = token): Boolean {
        if (expected == null || token !== expected) return false
        token = null
        active = null
        return true
    }
}
