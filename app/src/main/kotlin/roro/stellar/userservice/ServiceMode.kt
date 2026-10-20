// Source: https://github.com/roro2239/Stellar-API (userservice module, v1.0.3), MPL-2.0.
// Vendored because the published Stellar-API aggregate artifact does not include this module.
package roro.stellar.userservice

enum class ServiceMode(val value: Int) {
    ONE_TIME(0),

    DAEMON(1);

    companion object {
        @JvmStatic
        fun fromValue(value: Int): ServiceMode = entries.find { it.value == value } ?: ONE_TIME
    }
}
