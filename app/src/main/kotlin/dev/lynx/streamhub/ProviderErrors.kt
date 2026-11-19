package dev.lynx.streamhub

class AddonCapabilityException(val userExplanation:String):IllegalStateException(userExplanation)

object ProviderErrors {
    fun missingSymbol(error:Throwable):String? {
        val binary=Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:[./][A-Za-z_$][A-Za-z0-9_$]*)+")
        for(cause in generateSequence(error){it.cause}.take(8)) {
            if(cause !is LinkageError && cause !is ClassNotFoundException)continue
            val message=cause.message.orEmpty()
            val candidate=when {
                binary.matches(message)->message
                else->Regex("(?:Failed resolution of: L)([A-Za-z0-9_$/]+);").find(message)?.groupValues?.get(1)
                    ?: Regex("class [\"]([^\"]+)[\"]").find(message)?.groupValues?.get(1)
            }
            if(candidate!=null && binary.matches(candidate))return candidate.replace('/','.').take(200)
        }
        return null
    }
    fun summary(error:Throwable):String = when {
        error is AddonCapabilityException -> error.userExplanation
        generateSequence(error){it.cause}.take(8).any{it is LinkageError || it is ClassNotFoundException}->
            "This extension requires an unavailable/incompatible host API. Update the extension, or export diagnostics to identify the missing class."
        error is java.net.SocketTimeoutException || error is java.net.ConnectException ->
            "The provider connection timed out or could not connect. Retry, check your network, or try again later."
        error is java.net.UnknownHostException -> "The provider host could not be resolved. Check your network/private DNS or retry later."
        else -> "The provider could not load this title. Retry or export diagnostics."
    }
}
