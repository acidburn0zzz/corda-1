package protocols

import co.paralleluniverse.fibers.Suspendable
import com.r3corda.core.contracts.*
import com.r3corda.core.crypto.DigitalSignature
import com.r3corda.core.crypto.Party
import com.r3corda.core.crypto.signWithECDSA
import com.r3corda.core.messaging.Ack
import com.r3corda.core.node.NodeInfo
import com.r3corda.core.protocols.ProtocolLogic
import com.r3corda.core.random63BitValue
import com.r3corda.core.utilities.ProgressTracker
import com.r3corda.protocols.AbstractRequestMessage
import com.r3corda.protocols.NotaryProtocol
import com.r3corda.protocols.ResolveTransactionsProtocol
import java.security.PublicKey

/**
 * Abstract protocol to be used for replacing one state with another, for example when changing the notary of a state.
 * Notably this requires a one to one replacement of states, states cannot be split, merged or issued as part of these
 * protocols.
 *
 * The [Instigator] assembles the transaction for state replacement and sends out change proposals to all participants
 * ([Acceptor]) of that state. If participants agree to the proposed change, they each sign the transaction.
 * Finally, [Instigator] sends the transaction containing all signatures back to each participant so they can record it and
 * use the new updated state for future transactions.
 */
abstract class AbstractStateReplacementProtocol<T> {
    interface Proposal<T> {
        val stateRef: StateRef
        val modification: T
        val stx: SignedTransaction
    }

    class Handshake(val sessionIdForSend: Long,
                    replyTo: Party,
                    override val sessionID: Long) : AbstractRequestMessage(replyTo)

    abstract class Instigator<S : ContractState, T>(val originalState: StateAndRef<S>,
                                                    val modification: T,
                                                    override val progressTracker: ProgressTracker = tracker()) : ProtocolLogic<StateAndRef<S>>() {
        companion object {

            object SIGNING : ProgressTracker.Step("Requesting signatures from other parties")

            object NOTARY : ProgressTracker.Step("Requesting notary signature")

            fun tracker() = ProgressTracker(SIGNING, NOTARY)
        }

        abstract val TOPIC_CHANGE: String
        abstract val TOPIC_INITIATE: String

        @Suspendable
        override fun call(): StateAndRef<S> {
            val (stx, participants) = assembleTx()

            progressTracker.currentStep = SIGNING

            val myKey = serviceHub.storageService.myLegalIdentity.owningKey
            val me = listOf(myKey)

            val signatures = if (participants == me) {
                listOf(getNotarySignature(stx))
            } else {
                collectSignatures(participants - me, stx)
            }

            val finalTx = stx + signatures
            serviceHub.recordTransactions(listOf(finalTx))
            return finalTx.tx.outRef(0)
        }

        abstract internal fun assembleProposal(stateRef: StateRef, modification: T, stx: SignedTransaction): Proposal<T>
        abstract internal fun assembleTx(): Pair<SignedTransaction, List<PublicKey>>

        @Suspendable
        private fun collectSignatures(participants: List<PublicKey>, stx: SignedTransaction): List<DigitalSignature.WithKey> {
            val sessions = mutableMapOf<NodeInfo, Long>()

            val participantSignatures = participants.map {
                val participantNode = serviceHub.networkMapCache.getNodeByPublicKey(it) ?:
                        throw IllegalStateException("Participant $it to state $originalState not found on the network")
                val sessionIdForSend = random63BitValue()
                sessions[participantNode] = sessionIdForSend

                getParticipantSignature(participantNode, stx, sessionIdForSend)
            }

            val allSignatures = participantSignatures + getNotarySignature(stx)
            sessions.forEach { send(TOPIC_CHANGE, it.key.identity, it.value, allSignatures) }

            return allSignatures
        }

        @Suspendable
        private fun getParticipantSignature(node: NodeInfo, stx: SignedTransaction, sessionIdForSend: Long): DigitalSignature.WithKey {
            val sessionIdForReceive = random63BitValue()
            val proposal = assembleProposal(originalState.ref, modification, stx)

            val handshake = Handshake(sessionIdForSend, serviceHub.storageService.myLegalIdentity, sessionIdForReceive)
            sendAndReceive<Ack>(TOPIC_INITIATE, node.identity, 0, sessionIdForReceive, handshake)

            val response = sendAndReceive<Result>(TOPIC_CHANGE, node.identity, sessionIdForSend, sessionIdForReceive, proposal)
            val participantSignature = response.validate {
                if (it.sig == null) throw StateReplacementException(it.error!!)
                else {
                    check(it.sig.by == node.identity.owningKey) { "Not signed by the required participant" }
                    it.sig.verifyWithECDSA(stx.txBits)
                    it.sig
                }
            }

            return participantSignature
        }

        @Suspendable
        private fun getNotarySignature(stx: SignedTransaction): DigitalSignature.LegallyIdentifiable {
            progressTracker.currentStep = NOTARY
            return subProtocol(NotaryProtocol.Client(stx))
        }
    }

    abstract class Acceptor<T>(val otherSide: Party,
                               val sessionIdForSend: Long,
                               val sessionIdForReceive: Long,
                               override val progressTracker: ProgressTracker = tracker()) : ProtocolLogic<Unit>() {

        companion object {
            object VERIFYING : ProgressTracker.Step("Verifying state replacement proposal")

            object APPROVING : ProgressTracker.Step("State replacement approved")

            object REJECTING : ProgressTracker.Step("State replacement rejected")

            fun tracker() = ProgressTracker(VERIFYING, APPROVING, REJECTING)
        }

        abstract val TOPIC_CHANGE: String
        abstract val TOPIC_INITIATE: String

        @Suspendable
        override fun call() {
            progressTracker.currentStep = VERIFYING
            val proposal = receive<Proposal<T>>(TOPIC_CHANGE, sessionIdForReceive).validate { it }

            try {
                verifyProposal(proposal)
                verifyTx(proposal.stx)
            } catch(e: Exception) {
                // TODO: catch only specific exceptions. However, there are numerous validation exceptions
                //       that might occur (tx validation/resolution, invalid proposal). Need to rethink how
                //       we manage exceptions and maybe introduce some platform exception hierarchy
                val myIdentity = serviceHub.storageService.myLegalIdentity
                val state = proposal.stateRef
                val reason = StateReplacementRefused(myIdentity, state, e.message)

                reject(reason)
                return
            }

            approve(proposal.stx)
        }

        @Suspendable
        private fun approve(stx: SignedTransaction) {
            progressTracker.currentStep = APPROVING

            val mySignature = sign(stx)
            val response = Result.noError(mySignature)
            val swapSignatures = sendAndReceive<List<DigitalSignature.WithKey>>(TOPIC_CHANGE, otherSide, sessionIdForSend, sessionIdForReceive, response)

            val allSignatures = swapSignatures.validate { signatures ->
                signatures.forEach { it.verifyWithECDSA(stx.txBits) }
                signatures
            }

            val finalTx = stx + allSignatures
            finalTx.verify()
            serviceHub.recordTransactions(listOf(finalTx))
        }

        @Suspendable
        private fun reject(e: StateReplacementRefused) {
            progressTracker.currentStep = REJECTING
            val response = Result.withError(e)
            send(TOPIC_CHANGE, otherSide, sessionIdForSend, response)
        }

        /**
         * Check the state change proposal to confirm that it's acceptable to this node. Rules for verification depend
         * on the change proposed, and may further depend on the node itself (for example configuration).
         */
        abstract internal fun verifyProposal(proposal: Proposal<T>)

        @Suspendable
        private fun verifyTx(stx: SignedTransaction) {
            checkMySignatureRequired(stx.tx)
            checkDependenciesValid(stx)
            checkValid(stx)
        }

        private fun checkMySignatureRequired(tx: WireTransaction) {
            // TODO: use keys from the keyManagementService instead
            val myKey = serviceHub.storageService.myLegalIdentity.owningKey
            require(tx.signers.contains(myKey)) { "Party is not a participant for any of the input states of transaction ${tx.id}" }
        }

        @Suspendable
        private fun checkDependenciesValid(stx: SignedTransaction) {
            val dependencyTxIDs = stx.tx.inputs.map { it.txhash }.toSet()
            subProtocol(ResolveTransactionsProtocol(dependencyTxIDs, otherSide))
        }

        private fun checkValid(stx: SignedTransaction) {
            val ltx = stx.tx.toLedgerTransaction(serviceHub.identityService, serviceHub.storageService.attachments)
            serviceHub.verifyTransaction(ltx)
        }

        private fun sign(stx: SignedTransaction): DigitalSignature.WithKey {
            val myKeyPair = serviceHub.storageService.myLegalIdentityKey
            return myKeyPair.signWithECDSA(stx.txBits)
        }
    }

    // TODO: similar classes occur in other places (NotaryProtocol), need to consolidate
    data class Result private constructor(val sig: DigitalSignature.WithKey?, val error: StateReplacementRefused?) {
        companion object {
            fun withError(error: StateReplacementRefused) = Result(null, error)
            fun noError(sig: DigitalSignature.WithKey) = Result(sig, null)
        }
    }
}


/** Thrown when a participant refuses proposed the state replacement */
class StateReplacementRefused(val identity: Party, val state: StateRef, val detail: String?) {
    override fun toString(): String
            = "A participant $identity refused to change state $state"
}

class StateReplacementException(val error: StateReplacementRefused)
: Exception("State change failed - $error")