package net.corda.confidential

import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.serialize
import net.corda.core.utilities.getOrThrow
import net.corda.testing.ALICE
import net.corda.testing.BOB
import net.corda.testing.DUMMY_NOTARY
import net.corda.testing.chooseIdentity
import net.corda.testing.*
import net.corda.testing.node.MockNetwork
import org.junit.Test
import java.util.*
import kotlin.test.*

class SwapIdentitiesFlowTests {
    @Test
    fun `issue key`() {
        // We run this in parallel threads to help catch any race conditions that may exist.
        val mockNet = MockNetwork(false, true)

        // Set up values we'll need
        val notaryNode = mockNet.createNotaryNode(null, DUMMY_NOTARY.name)
        val aliceNode = mockNet.createPartyNode(notaryNode.network.myAddress, ALICE.name)
        val bobNode = mockNet.createPartyNode(notaryNode.network.myAddress, BOB.name)
        val alice: Party = aliceNode.services.myInfo.chooseIdentity()
        val bob: Party = bobNode.services.myInfo.chooseIdentity()
        mockNet.registerIdentities()

        // Run the flows
        val requesterFlow = aliceNode.services.startFlow(SwapIdentitiesFlow(bob))

        // Get the results
        val actual: Map<Party, AnonymousParty> = requesterFlow.resultFuture.getOrThrow().toMap()
        assertEquals(2, actual.size)
        // Verify that the generated anonymous identities do not match the well known identities
        val aliceAnonymousIdentity = actual[alice] ?: throw IllegalStateException()
        val bobAnonymousIdentity = actual[bob] ?: throw IllegalStateException()
        assertNotEquals<AbstractParty>(alice, aliceAnonymousIdentity)
        assertNotEquals<AbstractParty>(bob, bobAnonymousIdentity)

        // Verify that the anonymous identities look sane
        assertEquals(alice.name, aliceNode.database.transaction { aliceNode.services.identityService.wellKnownPartyFromAnonymous(aliceAnonymousIdentity)!!.name })
        assertEquals(bob.name, bobNode.database.transaction { bobNode.services.identityService.wellKnownPartyFromAnonymous(bobAnonymousIdentity)!!.name })

        // Verify that the nodes have the right anonymous identities
        assertTrue { aliceAnonymousIdentity.owningKey in aliceNode.services.keyManagementService.keys }
        assertTrue { bobAnonymousIdentity.owningKey in bobNode.services.keyManagementService.keys }
        assertFalse { aliceAnonymousIdentity.owningKey in bobNode.services.keyManagementService.keys }
        assertFalse { bobAnonymousIdentity.owningKey in aliceNode.services.keyManagementService.keys }

        mockNet.stopNodes()
    }

    @Test
    fun `data to sign must not be defined by counterparty`() {
        val fixedRand = Random(20170914L)
        val identity = SerializedBytes<PartyAndCertificate>(ByteArray(SwapIdentitiesFlow.NONCE_SIZE_BYTES) { 0 }.apply(fixedRand::nextBytes))
        val aliceNonce = ByteArray(SwapIdentitiesFlow.NONCE_SIZE_BYTES) { 0 }.apply(fixedRand::nextBytes)
        val bobNonce = ByteArray(SwapIdentitiesFlow.NONCE_SIZE_BYTES) { 0 }.apply(fixedRand::nextBytes)
        val validNonces = TreeSet(SwapIdentitiesFlow.ArrayComparator).apply {
            add(aliceNonce)
            add(bobNonce)
        }
        val sigData = SwapIdentitiesFlow.buildDataToSign(identity, validNonces)
        assertTrue { arrayContains(sigData, identity.bytes) }
        assertFalse { arrayContains(sigData, aliceNonce) }
        assertFalse { arrayContains(sigData, bobNonce) }
    }

    private fun arrayContains(outerArray: ByteArray, innerArray: ByteArray): Boolean {
        val searchSpace = outerArray.size - innerArray.size
        if (searchSpace >= 0) {
            for (outerIdx in 0 until searchSpace) {
                var matchedIdx = 0
                for (innerIdx in 0 until innerArray.size) {
                    if (outerArray[outerIdx + innerIdx] != innerArray[innerIdx])
                        break
                    matchedIdx = innerIdx
                }
                if (matchedIdx == innerArray.size - 1)
                    return true
            }
        }
        return false
    }

    /**
     * Check that flow is actually validating the name on the certificate presented by the counterparty.
     */
    @Test
    fun `verifies identity name`() {
        // We run this in parallel threads to help catch any race conditions that may exist.
        val mockNet = MockNetwork(false, true)

        // Set up values we'll need
        val fixedRand = Random(20170914L)
        val notaryNode = mockNet.createNotaryNode(null, DUMMY_NOTARY.name)
        val aliceNode = mockNet.createPartyNode(notaryNode.network.myAddress, ALICE.name)
        val bobNode = mockNet.createPartyNode(notaryNode.network.myAddress, BOB.name)
        val bob: Party = bobNode.services.myInfo.chooseIdentity()
        val aliceNonce = ByteArray(SwapIdentitiesFlow.NONCE_SIZE_BYTES) { 0 }.apply(fixedRand::nextBytes)
        val bobNonce = ByteArray(SwapIdentitiesFlow.NONCE_SIZE_BYTES) { 0 }.apply(fixedRand::nextBytes)
        val validNonces = TreeSet(SwapIdentitiesFlow.ArrayComparator).apply {
            add(aliceNonce)
            add(bobNonce)
        }
        val notBob = notaryNode.database.transaction {
            notaryNode.services.keyManagementService.freshKeyAndCert(notaryNode.services.myInfo.chooseIdentityAndCert(), false)
        }
        val notBobBytes = SerializedBytes<PartyAndCertificate>(notBob.serialize().bytes)
        val sigData = SwapIdentitiesFlow.buildDataToSign(notBobBytes, validNonces)
        val signature = notaryNode.services.keyManagementService.sign(sigData, notBob.owningKey)
        assertFailsWith<SwapIdentitiesException>("Certificate subject must match counterparty's well known identity.") {
            SwapIdentitiesFlow.validateAndRegisterIdentity(aliceNode.services.identityService, bob, notBobBytes, validNonces, signature.withoutKey())
        }

        mockNet.stopNodes()
    }

    /**
     * Check that flow is actually validating its the signature presented by the counterparty.
     */
    @Test
    fun `verifies signature`() {
        // We run this in parallel threads to help catch any race conditions that may exist.
        val mockNet = MockNetwork(false, true)

        // Set up values we'll need
        val fixedRand = Random(20170914L)
        val notaryNode = mockNet.createNotaryNode(null, DUMMY_NOTARY.name)
        val aliceNode = mockNet.createPartyNode(notaryNode.network.myAddress, ALICE.name)
        val bobNode = mockNet.createPartyNode(notaryNode.network.myAddress, BOB.name)
        val bob: Party = bobNode.services.myInfo.chooseIdentity()
        val aliceNonce = ByteArray(SwapIdentitiesFlow.NONCE_SIZE_BYTES) { 0 }.apply(fixedRand::nextBytes)
        val bobNonce = ByteArray(SwapIdentitiesFlow.NONCE_SIZE_BYTES) { 0 }.apply(fixedRand::nextBytes)
        val wrongNonce = ByteArray(SwapIdentitiesFlow.NONCE_SIZE_BYTES) { 0 }.apply(fixedRand::nextBytes)
        val validNonces = TreeSet(SwapIdentitiesFlow.ArrayComparator).apply {
            add(aliceNonce)
            add(bobNonce)
        }
        val invalidNonces = TreeSet(SwapIdentitiesFlow.ArrayComparator).apply {
            add(aliceNonce)
            add(wrongNonce)
        }
        // Check that the right signing key but the wrong nonce is rejected
        bobNode.database.transaction {
            bobNode.services.keyManagementService.freshKeyAndCert(bobNode.services.myInfo.chooseIdentityAndCert(), false)
        }.let { anonymousBob ->
            val anonymousBobBytes = SerializedBytes<PartyAndCertificate>(anonymousBob.serialize().bytes)
            val sigData = SwapIdentitiesFlow.buildDataToSign(anonymousBobBytes, invalidNonces)
            val signature = bobNode.services.keyManagementService.sign(sigData, anonymousBob.owningKey)
            assertFailsWith<SwapIdentitiesException>("Signature does not match the given identity and nonce.") {
                SwapIdentitiesFlow.validateAndRegisterIdentity(aliceNode.services.identityService, bob, anonymousBobBytes, validNonces, signature.withoutKey())
            }
        }
        // Check that the wrong signature with the correct nonce is rejected
        notaryNode.database.transaction {
            notaryNode.services.keyManagementService.freshKeyAndCert(notaryNode.services.myInfo.chooseIdentityAndCert(), false)
        }.let { anonymousNotary ->
            val anonymousNotaryBytes = SerializedBytes<PartyAndCertificate>(anonymousNotary.serialize().bytes)
            val sigData = SwapIdentitiesFlow.buildDataToSign(anonymousNotaryBytes, validNonces)
            val signature = notaryNode.services.keyManagementService.sign(sigData, anonymousNotary.owningKey)
            assertFailsWith<SwapIdentitiesException>("Signature does not match the given identity and nonce") {
                SwapIdentitiesFlow.validateAndRegisterIdentity(aliceNode.services.identityService, bob, anonymousNotaryBytes, validNonces, signature.withoutKey())
            }
        }
        // Check that the right signing key, right nonce but wrong identity is rejected
        val anonymousAlice = aliceNode.database.transaction {
            aliceNode.services.keyManagementService.freshKeyAndCert(aliceNode.services.myInfo.chooseIdentityAndCert(), false)
        }
        bobNode.database.transaction {
            bobNode.services.keyManagementService.freshKeyAndCert(bobNode.services.myInfo.chooseIdentityAndCert(), false)
        }.let { anonymousBob ->
            val anonymousAliceBytes = SerializedBytes<PartyAndCertificate>(anonymousAlice.serialize().bytes)
            val anonymousBobBytes = SerializedBytes<PartyAndCertificate>(anonymousBob.serialize().bytes)
            val sigData = SwapIdentitiesFlow.buildDataToSign(anonymousAliceBytes, validNonces)
            val signature = bobNode.services.keyManagementService.sign(sigData, anonymousBob.owningKey)
            assertFailsWith<SwapIdentitiesException>("Signature does not match the given identity and nonce.") {
                SwapIdentitiesFlow.validateAndRegisterIdentity(aliceNode.services.identityService, bob, anonymousBobBytes, validNonces, signature.withoutKey())
            }
        }

        mockNet.stopNodes()
    }
}
