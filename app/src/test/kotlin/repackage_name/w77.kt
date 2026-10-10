package repackage_name

import com.vivo.ai.chat.MessageExtents
import com.vivo.ai.chat.MessageParams

/** Minimal append-only BlueLM accumulator contract, verified against 6.8.5.3. */
class w77 {
    @JvmField var a: MessageExtents? = null
    @JvmField var b: MessageParams? = null
    fun onResult(request: MessageParams?, result: MessageParams) {
        val data = result.gptParams.data as MessageExtents
        data.text = if (result.gptParams.is_last()) a?.text ?: data.text else (a?.text ?: "") + data.text
        a = data
        b = result
    }
}
