package dev.lynx.streamhub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Only idempotent metadata reads. One retry, with cancellation propagated. */
object ProviderRequests {
    private data class Response<T>(val value:T?)
    suspend fun <T:Any> details(block:suspend ()->T?):T {
        repeat(2){attempt->
            try {
                val response=withTimeoutOrNull(40_000L){Response(block())}
                    ?: throw java.net.SocketTimeoutException("Provider details unavailable within metadata deadline")
                return response.value ?: error("Provider returned no title metadata")
            }catch(cancel:CancellationException){throw cancel
            }catch(error:java.io.IOException){
                Diagnostics.failure("provider.details.network",error)
                if(attempt==1)throw error
                Diagnostics.event("provider.details.retry","attempt=1")
                delay(1000L)
            }
        }
        error("Unreachable")
    }
}
