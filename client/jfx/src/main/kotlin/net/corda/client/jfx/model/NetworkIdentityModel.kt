package net.corda.client.jfx.model

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import net.corda.client.jfx.utils.filterNotNull
import net.corda.client.jfx.utils.fold
import net.corda.client.jfx.utils.map
import net.corda.client.jfx.utils.toObservableList
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.NetworkMapCache.MapChange
import java.security.PublicKey

class NetworkIdentityModel {
    private val networkIdentityObservable by observable(NodeMonitorModel::networkMap)

    val networkIdentities: ObservableList<NodeInfo> =
            networkIdentityObservable.fold(FXCollections.observableArrayList()) { list, update ->
                list.removeIf {
                    when (update) {
                        is MapChange.Removed -> it == update.node
                        is MapChange.Modified -> it == update.previousNode
                        else -> false
                    }
                }
                list.addAll(update.node)
            }

    private val rpcProxy by observableValue(NodeMonitorModel::proxyObservable)

    private val identityCache = CacheBuilder.newBuilder()
            .build<PublicKey, ObservableValue<NodeInfo?>>(CacheLoader.from { publicKey ->
                publicKey?.let { rpcProxy.map { it?.nodeInfoFromParty(AnonymousParty(publicKey)) } }
            })

    val notaries: ObservableList<Party> = rpcProxy.toObservableList { it?.notaryIdentities() ?: emptyList() }
    val notaryNodes: ObservableList<NodeInfo> = notaries.map { rpcProxy.value?.nodeInfoFromParty(it) }.filterNotNull()
    val parties: ObservableList<NodeInfo> = networkIdentities.filtered { it.legalIdentities.all { it !in notaries } }
    val myIdentity = rpcProxy.map { it?.nodeInfo()?.legalIdentitiesAndCerts?.first()?.party }

    fun partyFromPublicKey(publicKey: PublicKey): ObservableValue<NodeInfo?> = identityCache[publicKey]
}
