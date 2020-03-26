package cn.edu.sustech.cse.miband

import java.util.*

private fun shortUuid(id: String) = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")

val UUID_CLIENT_CHAR_CONFIG: UUID = shortUuid("2902")

val UUID_SERVICE_MIBAND1: UUID = shortUuid("fee0")
val UUID_SERVICE_MIBAND2: UUID = shortUuid("fee1")
val UUID_SERVICE_HEART_RATE: UUID = shortUuid("180d")

val UUID_CHARACTERISTIC_AUTH: UUID = UUID.fromString("00000009-0000-3512-2118-0009af100700")
