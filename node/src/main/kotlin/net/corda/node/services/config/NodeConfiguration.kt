package net.corda.node.services.config

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.node.internal.NetworkMapInfo
import net.corda.nodeapi.internal.ServiceInfo
import net.corda.node.services.messaging.CertificateChainCheckPolicy
import net.corda.node.services.network.NetworkMapService
import net.corda.nodeapi.User
import net.corda.nodeapi.config.NodeSSLConfiguration
import net.corda.nodeapi.config.OldConfig
import java.net.URL
import java.nio.file.Path
import java.util.*

/** @param exposeRaces for testing only, so its default is not in reference.conf but here. */
data class BFTSMaRtConfiguration(val replicaId: Int, val debug: Boolean, val exposeRaces: Boolean = false) {
    fun isValid() = replicaId >= 0
}

interface NodeConfiguration : NodeSSLConfiguration {
    // myLegalName should be only used in the initial network registration, we should use the name from the certificate instead of this.
    // TODO: Remove this so we don't accidentally use this identity in the code?
    val myLegalName: CordaX500Name
    val networkMapService: NetworkMapInfo?
    val minimumPlatformVersion: Int
    val emailAddress: String
    val exportJMXto: String
    val dataSourceProperties: Properties
    val database: Properties?
    val rpcUsers: List<User>
    val devMode: Boolean
    val certificateSigningService: URL
    val certificateChainCheckPolicies: List<CertChainPolicyConfig>
    val verifierType: VerifierType
    val messageRedeliveryDelaySeconds: Int
    val bftSMaRt: BFTSMaRtConfiguration
    val notaryNodeAddress: NetworkHostAndPort?
    val notaryClusterAddresses: List<NetworkHostAndPort>
}

data class FullNodeConfiguration(
        /** This is not retrieved from the config file but rather from a command line argument. */
        override val baseDirectory: Path,
        override val myLegalName: CordaX500Name,
        override val emailAddress: String,
        override val keyStorePassword: String,
        override val trustStorePassword: String,
        override val dataSourceProperties: Properties,
        override val database: Properties?,
        override val certificateSigningService: URL,
        override val networkMapService: NetworkMapInfo?,
        override val minimumPlatformVersion: Int = 1,
        override val rpcUsers: List<User>,
        override val verifierType: VerifierType,
        override val messageRedeliveryDelaySeconds: Int = 30,
        val useHTTPS: Boolean,
        @OldConfig("artemisAddress")
        val p2pAddress: NetworkHostAndPort,
        val rpcAddress: NetworkHostAndPort?,
        // TODO This field is slightly redundant as p2pAddress is sufficient to hold the address of the node's MQ broker.
        // Instead this should be a Boolean indicating whether that broker is an internal one started by the node or an external one
        val messagingServerAddress: NetworkHostAndPort?,
        val extraAdvertisedServiceIds: List<String>,
        override val bftSMaRt: BFTSMaRtConfiguration,
        override val notaryNodeAddress: NetworkHostAndPort?,
        override val notaryClusterAddresses: List<NetworkHostAndPort>,
        override val certificateChainCheckPolicies: List<CertChainPolicyConfig>,
        override val devMode: Boolean = false,
        val useTestClock: Boolean = false,
        val detectPublicIp: Boolean = true
) : NodeConfiguration {
    override val exportJMXto: String get() = "http"

    init {
        // This is a sanity feature do not remove.
        require(!useTestClock || devMode) { "Cannot use test clock outside of dev mode" }
        // TODO Move this to ArtemisMessagingServer
        rpcUsers.forEach {
            require(it.username.matches("\\w+".toRegex())) { "Username ${it.username} contains invalid characters" }
        }
        require(myLegalName.commonName == null) { "Common name must be null: $myLegalName" }
    }

    fun calculateServices(): Set<ServiceInfo> {
        val advertisedServices = extraAdvertisedServiceIds
                .filter(String::isNotBlank)
                .map { ServiceInfo.parse(it) }
                .toMutableSet()
        if (networkMapService == null) advertisedServices += ServiceInfo(NetworkMapService.type)
        return advertisedServices
    }
}

enum class VerifierType {
    InMemory,
    OutOfProcess
}

enum class CertChainPolicyType {
    Any,
    RootMustMatch,
    LeafMustMatch,
    MustContainOneOf
}

data class CertChainPolicyConfig(val role: String, val policy: CertChainPolicyType, val trustedAliases: Set<String>) {
    val certificateChainCheckPolicy: CertificateChainCheckPolicy get() {
        return when (policy) {
            CertChainPolicyType.Any -> CertificateChainCheckPolicy.Any
            CertChainPolicyType.RootMustMatch -> CertificateChainCheckPolicy.RootMustMatch
            CertChainPolicyType.LeafMustMatch -> CertificateChainCheckPolicy.LeafMustMatch
            CertChainPolicyType.MustContainOneOf -> CertificateChainCheckPolicy.MustContainOneOf(trustedAliases)
        }
    }
}
