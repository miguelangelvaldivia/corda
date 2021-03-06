package net.corda.client.rpc

import net.corda.core.serialization.CordaSerializable

/**
 * Thrown to indicate that the calling user does not have permission for something they have requested (for example
 * calling a method).
 */
@CordaSerializable
class PermissionException(msg: String) : RuntimeException(msg)
