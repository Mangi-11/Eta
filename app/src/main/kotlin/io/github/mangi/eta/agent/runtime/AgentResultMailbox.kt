package io.github.mangi.eta.agent.runtime

import android.os.Bundle
import java.io.Closeable

/** Handler 接收，等待线程读取；超时或取消后到达的结果也必须释放文件描述符。 */
internal class AgentResultMailbox : Closeable {
    private var result: Bundle? = null
    private var closed = false

    @Synchronized
    fun set(bundle: Bundle) {
        if (closed || result != null) AgentWireText.close(bundle) else result = bundle
    }

    @Synchronized
    fun get(): Bundle? = result

    @Synchronized
    override fun close() {
        closed = true
        result?.let(AgentWireText::close)
        result = null
    }
}
