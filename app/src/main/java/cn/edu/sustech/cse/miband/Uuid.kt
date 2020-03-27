package cn.edu.sustech.cse.miband

import java.util.*

private fun shortUuid(id: String) = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")
private fun privateUuid(id: String) = UUID.fromString("0000$id-0000-3512-2118-0009af100700")

val UUID_CLIENT_CHAR_CONFIG: UUID = shortUuid("2902")

val UUID_SERVICE_MIBAND1: UUID = shortUuid("fee0")
val UUID_SERVICE_MIBAND2: UUID = shortUuid("fee1")
val UUID_SERVICE_HEART_RATE: UUID = shortUuid("180d")

val UUID_CHAR_AUTH: UUID = privateUuid("0009")
val UUID_CHAR_STEPS: UUID = privateUuid("0007")
val UUID_CHAR_FETCH: UUID = privateUuid("0004")
val UUID_CHAR_ACTIVITY_DATA: UUID = privateUuid("0005")
