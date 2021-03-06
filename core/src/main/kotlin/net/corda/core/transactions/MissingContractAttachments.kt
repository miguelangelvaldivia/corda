package net.corda.core.transactions

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.TransactionState
import net.corda.core.serialization.CordaSerializable

/**
 * A contract attachment was missing when trying to automatically attach all known contract attachments
 *
 * @property states States which have contracts that do not have corresponding attachments in the attachment store.
 */
@CordaSerializable
class MissingContractAttachments(val states: List<TransactionState<ContractState>>)
    : Exception("Cannot find contract attachments for ${states.map { it.contract }.distinct() }")