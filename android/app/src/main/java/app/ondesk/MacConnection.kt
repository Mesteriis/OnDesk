package app.ondesk

import java.io.IOException

data class MacConnection<T>(val client: T, val address: Pair<String, Int>)

// Every attempt uses the caller's unchanged certificate pin, including NSD results.
suspend fun <T> connectRememberedMac(host: String, port: Int, discover: suspend () -> Pair<String, Int>?, open: (String, Int) -> T): MacConnection<T> {
    val previous = try { return MacConnection(open(host, port), host to port) }
        catch (error: IOException) { error }
    val address = discover() ?: throw previous
    return MacConnection(open(address.first, address.second), address)
}
