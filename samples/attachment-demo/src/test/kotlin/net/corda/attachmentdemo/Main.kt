package net.corda.attachmentdemo

import com.google.common.net.HttpHeaders.CONTENT_DISPOSITION
import joptsimple.OptionParser
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.concurrent.CordaFuture
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.Party
import net.corda.core.internal.Emoji
import net.corda.core.internal.InputStreamAndHash
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.getOrThrow
import net.corda.testing.DUMMY_BANK_B
import net.corda.testing.DUMMY_NOTARY
import net.corda.testing.driver.poll
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.jar.JarInputStream
import kotlin.system.exitProcess

internal enum class Role {
    SENDER,
    RECIPIENT
}

fun main(args: Array<String>) {
    val parser = OptionParser()

    val roleArg = parser.accepts("role").withRequiredArg().ofType(Role::class.java).required()
    val options = try {
        parser.parse(*args)
    } catch (e: Exception) {
        println(e.message)
        printHelp(parser)
        exitProcess(1)
    }

    val role = options.valueOf(roleArg)!!
    when (role) {
        Role.SENDER -> {
            val host = NetworkHostAndPort("localhost", 10006)
            println("Connecting to sender node ($host)")
            CordaRPCClient(host).start("demo", "demo").use {
                sender(it.proxy)
            }
        }
        Role.RECIPIENT -> {
            val host = NetworkHostAndPort("localhost", 10009)
            println("Connecting to the recipient node ($host)")
            CordaRPCClient(host).start("demo", "demo").use {
                recipient(it.proxy)
            }
        }
    }
}

/** An in memory test zip attachment of at least numOfClearBytes size, will be used. */
fun sender(rpc: CordaRPCOps, numOfClearBytes: Int = 1024) { // default size 1K.
    val (inputStream, hash) = InputStreamAndHash.createInMemoryTestZip(numOfClearBytes, 0)
    val executor = Executors.newScheduledThreadPool(2)
    try {
        sender(rpc, inputStream, hash, executor)
    } finally {
        executor.shutdown()
    }
}

private fun sender(rpc: CordaRPCOps, inputStream: InputStream, hash: SecureHash.SHA256, executor: ScheduledExecutorService) {

    // Get the identity key of the other side (the recipient).
    val notaryFuture: CordaFuture<Party> = poll(executor, DUMMY_NOTARY.name.toString()) { rpc.partyFromX500Name(DUMMY_NOTARY.name) }
    val otherSideFuture: CordaFuture<Party> = poll(executor, DUMMY_BANK_B.name.toString()) { rpc.partyFromX500Name(DUMMY_BANK_B.name) }

    // Make sure we have the file in storage
    if (!rpc.attachmentExists(hash)) {
        inputStream.use {
            val id = rpc.uploadAttachment(it)
            require(hash == id) { "Id was '$id' instead of '$hash'" }
        }
        require(rpc.attachmentExists(hash))
    }

    val flowHandle = rpc.startTrackedFlow(::AttachmentDemoFlow, otherSideFuture.get(), notaryFuture.get(), hash)
    flowHandle.progress.subscribe(::println)
    val stx = flowHandle.returnValue.getOrThrow()
    println("Sent ${stx.id}")
}

fun recipient(rpc: CordaRPCOps) {
    println("Waiting to receive transaction ...")
    val stx = rpc.internalVerifiedTransactionsFeed().updates.toBlocking().first()
    val wtx = stx.tx
    if (wtx.attachments.isNotEmpty()) {
        if (wtx.outputs.isNotEmpty()) {
            val state = wtx.outputsOfType<AttachmentContract.State>().single()
            require(rpc.attachmentExists(state.hash))

            // Download the attachment via the Web endpoint.
            val connection = URL("http://localhost:10010/attachments/${state.hash}").openConnection() as HttpURLConnection
            try {
                require(connection.responseCode == 200) { "HTTP status code was ${connection.responseCode}" }
                require(connection.contentType == "application/octet-stream") { "Content-Type header was ${connection.contentType}" }
                require(connection.contentLength > 1024) { "Attachment contains only ${connection.contentLength} bytes" }
                require(connection.getHeaderField(CONTENT_DISPOSITION) == "attachment; filename=\"${state.hash}.zip\"") {
                    "Content-Disposition header was ${connection.getHeaderField(CONTENT_DISPOSITION)}"
                }

                // Write out the entries inside this jar.
                println("Attachment JAR contains these entries:")
                JarInputStream(connection.inputStream).use { it ->
                    while (true) {
                        val e = it.nextJarEntry ?: break
                        println("Entry> ${e.name}")
                        it.closeEntry()
                    }
                }
            } finally {
                connection.disconnect()
            }
            println("File received - we're happy!\n\nFinal transaction is:\n\n${Emoji.renderIfSupported(wtx)}")
        } else {
            println("Error: no output state found in ${wtx.id}")
        }
    } else {
        println("Error: no attachments found in ${wtx.id}")
    }
}

private fun printHelp(parser: OptionParser) {
    println("""
    Usage: attachment-demo --role [RECIPIENT|SENDER] [options]
    Please refer to the documentation in docs/build/index.html for more info.

    """.trimIndent())
    parser.printHelpOn(System.out)
}