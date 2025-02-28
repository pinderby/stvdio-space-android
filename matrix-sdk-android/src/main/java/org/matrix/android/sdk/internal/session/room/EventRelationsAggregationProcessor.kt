/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.android.sdk.internal.session.room

import io.realm.Realm
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.crypto.verification.VerificationState
import org.matrix.android.sdk.api.session.events.model.AggregatedAnnotation
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.content.EncryptedEventContent
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.ReferencesAggregatedContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageBeaconLocationDataContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollResponseContent
import org.matrix.android.sdk.api.session.room.model.message.MessageRelationContent
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.api.session.room.powerlevels.PowerLevelsHelper
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.crypto.verification.toState
import org.matrix.android.sdk.internal.database.helper.findRootThreadEvent
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.EventMapper
import org.matrix.android.sdk.internal.database.model.EditAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.model.EditionOfEvent
import org.matrix.android.sdk.internal.database.model.EventAnnotationsSummaryEntity
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.ReactionAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.model.ReactionAggregatedSummaryEntityFields
import org.matrix.android.sdk.internal.database.model.ReferencesAggregatedSummaryEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntityFields
import org.matrix.android.sdk.internal.database.query.create
import org.matrix.android.sdk.internal.database.query.getOrCreate
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.EventInsertLiveProcessor
import org.matrix.android.sdk.internal.session.room.aggregation.livelocation.LiveLocationAggregationProcessor
import org.matrix.android.sdk.internal.session.room.aggregation.poll.PollAggregationProcessor
import org.matrix.android.sdk.internal.session.room.state.StateEventDataSource
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject

internal class EventRelationsAggregationProcessor @Inject constructor(
        @UserId private val userId: String,
        private val stateEventDataSource: StateEventDataSource,
        @SessionId private val sessionId: String,
        private val sessionManager: SessionManager,
        private val liveLocationAggregationProcessor: LiveLocationAggregationProcessor,
        private val pollAggregationProcessor: PollAggregationProcessor,
        private val clock: Clock,
) : EventInsertLiveProcessor {

    private val allowedTypes = listOf(
            EventType.MESSAGE,
            EventType.REDACTION,
            EventType.REACTION,
            EventType.KEY_VERIFICATION_DONE,
            EventType.KEY_VERIFICATION_CANCEL,
            EventType.KEY_VERIFICATION_ACCEPT,
            EventType.KEY_VERIFICATION_START,
            EventType.KEY_VERIFICATION_MAC,
            // TODO Add ?
            // EventType.KEY_VERIFICATION_READY,
            EventType.KEY_VERIFICATION_KEY,
            EventType.ENCRYPTED
    ) + EventType.POLL_START + EventType.POLL_RESPONSE + EventType.POLL_END + EventType.STATE_ROOM_BEACON_INFO + EventType.BEACON_LOCATION_DATA

    override fun shouldProcess(eventId: String, eventType: String, insertType: EventInsertType): Boolean {
        return allowedTypes.contains(eventType)
    }

    override suspend fun process(realm: Realm, event: Event) {
        try { // Temporary catch, should be removed
            val roomId = event.roomId
            if (roomId == null) {
                Timber.w("Event has no room id ${event.eventId}")
                return
            }
            val isLocalEcho = LocalEcho.isLocalEchoId(event.eventId ?: "")
            when (event.type) {
                EventType.REACTION                  -> {
                    // we got a reaction!!
                    Timber.v("###REACTION in room $roomId , reaction eventID ${event.eventId}")
                    handleReaction(realm, event, roomId, isLocalEcho)
                }
                EventType.MESSAGE                   -> {
                    if (event.unsignedData?.relations?.annotations != null) {
                        Timber.v("###REACTION Aggregation in room $roomId for event ${event.eventId}")
                        handleInitialAggregatedRelations(realm, event, roomId, event.unsignedData.relations.annotations)

                        EventAnnotationsSummaryEntity.where(realm, roomId, event.eventId ?: "").findFirst()
                                ?.let {
                                    TimelineEventEntity.where(realm, roomId = roomId, eventId = event.eventId ?: "").findAll()
                                            ?.forEach { tet -> tet.annotations = it }
                                }
                    }

                    val content: MessageContent? = event.content.toModel()
                    if (content?.relatesTo?.type == RelationType.REPLACE) {
                        Timber.v("###REPLACE in room $roomId for event ${event.eventId}")
                        // A replace!
                        handleReplace(realm, event, content, roomId, isLocalEcho)
                    }
                }

                EventType.KEY_VERIFICATION_DONE,
                EventType.KEY_VERIFICATION_CANCEL,
                EventType.KEY_VERIFICATION_ACCEPT,
                EventType.KEY_VERIFICATION_START,
                EventType.KEY_VERIFICATION_MAC,
                EventType.KEY_VERIFICATION_READY,
                EventType.KEY_VERIFICATION_KEY      -> {
                    Timber.v("## SAS REF in room $roomId for event ${event.eventId}")
                    event.content.toModel<MessageRelationContent>()?.relatesTo?.let {
                        if (it.type == RelationType.REFERENCE && it.eventId != null) {
                            handleVerification(realm, event, roomId, isLocalEcho, it.eventId)
                        }
                    }
                }

                EventType.ENCRYPTED                 -> {
                    // Relation type is in clear
                    val encryptedEventContent = event.content.toModel<EncryptedEventContent>()
                    if (encryptedEventContent?.relatesTo?.type == RelationType.REPLACE ||
                            encryptedEventContent?.relatesTo?.type == RelationType.RESPONSE
                    ) {
                        event.getClearContent().toModel<MessageContent>()?.let {
                            if (encryptedEventContent.relatesTo.type == RelationType.REPLACE) {
                                Timber.v("###REPLACE in room $roomId for event ${event.eventId}")
                                // A replace!
                                handleReplace(realm, event, it, roomId, isLocalEcho, encryptedEventContent.relatesTo.eventId)
                            } else if (event.getClearType() in EventType.POLL_RESPONSE) {
                                sessionManager.getSessionComponent(sessionId)?.session()?.let { session ->
                                    pollAggregationProcessor.handlePollResponseEvent(session, realm, event)
                                }
                            }
                        }
                    } else if (encryptedEventContent?.relatesTo?.type == RelationType.REFERENCE) {
                        when (event.getClearType()) {
                            EventType.KEY_VERIFICATION_DONE,
                            EventType.KEY_VERIFICATION_CANCEL,
                            EventType.KEY_VERIFICATION_ACCEPT,
                            EventType.KEY_VERIFICATION_START,
                            EventType.KEY_VERIFICATION_MAC,
                            EventType.KEY_VERIFICATION_READY,
                            EventType.KEY_VERIFICATION_KEY    -> {
                                Timber.v("## SAS REF in room $roomId for event ${event.eventId}")
                                encryptedEventContent.relatesTo.eventId?.let {
                                    handleVerification(realm, event, roomId, isLocalEcho, it)
                                }
                            }
                            in EventType.POLL_RESPONSE        -> {
                                event.getClearContent().toModel<MessagePollResponseContent>(catchError = true)?.let {
                                    sessionManager.getSessionComponent(sessionId)?.session()?.let { session ->
                                        pollAggregationProcessor.handlePollResponseEvent(session, realm, event)
                                    }
                                }
                            }
                            in EventType.POLL_END             -> {
                                sessionManager.getSessionComponent(sessionId)?.session()?.let { session ->
                                    getPowerLevelsHelper(event.roomId)?.let {
                                        pollAggregationProcessor.handlePollEndEvent(session, it, realm, event)
                                    }
                                }
                            }
                            in EventType.BEACON_LOCATION_DATA -> {
                                handleBeaconLocationData(event, realm, roomId, isLocalEcho)
                            }
                        }
                    } else if (encryptedEventContent?.relatesTo?.type == RelationType.ANNOTATION) {
                        // Reaction
                        if (event.getClearType() == EventType.REACTION) {
                            // we got a reaction!!
                            Timber.v("###REACTION e2e in room $roomId , reaction eventID ${event.eventId}")
                            handleReaction(realm, event, roomId, isLocalEcho)
                        }
                    }
                    // HandleInitialAggregatedRelations should also be applied in encrypted messages with annotations
//                    else if (event.unsignedData?.relations?.annotations != null) {
//                        Timber.v("###REACTION e2e Aggregation in room $roomId for event ${event.eventId}")
//                        handleInitialAggregatedRelations(realm, event, roomId, event.unsignedData.relations.annotations)
//                         EventAnnotationsSummaryEntity.where(realm, roomId, event.eventId ?: "").findFirst()
//                                 ?.let {
//                                     TimelineEventEntity.where(realm, roomId = roomId, eventId = event.eventId ?: "").findAll()
//                                             ?.forEach { tet -> tet.annotations = it }
//                                 }
//                    }
                }
                EventType.REDACTION                 -> {
                    val eventToPrune = event.redacts?.let { EventEntity.where(realm, eventId = it).findFirst() }
                            ?: return
                    when (eventToPrune.type) {
                        EventType.MESSAGE  -> {
                            Timber.d("REDACTION for message ${eventToPrune.eventId}")
//                                val unsignedData = EventMapper.map(eventToPrune).unsignedData
//                                        ?: UnsignedData(null, null)

                            // was this event a m.replace
                            val contentModel = ContentMapper.map(eventToPrune.content)?.toModel<MessageContent>()
                            if (RelationType.REPLACE == contentModel?.relatesTo?.type && contentModel.relatesTo?.eventId != null) {
                                handleRedactionOfReplace(realm, eventToPrune, contentModel.relatesTo!!.eventId!!)
                            }
                        }
                        EventType.REACTION -> {
                            handleReactionRedact(realm, eventToPrune)
                        }
                    }
                }
                in EventType.POLL_START             -> {
                    val content: MessagePollContent? = event.content.toModel()
                    if (content?.relatesTo?.type == RelationType.REPLACE) {
                        Timber.v("###REPLACE in room $roomId for event ${event.eventId}")
                        // A replace!
                        handleReplace(realm, event, content, roomId, isLocalEcho)
                    }
                }
                in EventType.POLL_RESPONSE          -> {
                    event.content.toModel<MessagePollResponseContent>(catchError = true)?.let {
                        sessionManager.getSessionComponent(sessionId)?.session()?.let { session ->
                            pollAggregationProcessor.handlePollResponseEvent(session, realm, event)
                        }
                    }
                }
                in EventType.POLL_END               -> {
                    sessionManager.getSessionComponent(sessionId)?.session()?.let { session ->
                        getPowerLevelsHelper(event.roomId)?.let {
                            pollAggregationProcessor.handlePollEndEvent(session, it, realm, event)
                        }
                    }
                }
                in EventType.STATE_ROOM_BEACON_INFO -> {
                    event.content.toModel<MessageBeaconInfoContent>(catchError = true)?.let {
                        liveLocationAggregationProcessor.handleBeaconInfo(realm, event, it, roomId, isLocalEcho)
                    }
                }
                in EventType.BEACON_LOCATION_DATA   -> {
                    handleBeaconLocationData(event, realm, roomId, isLocalEcho)
                }
                else                                -> Timber.v("UnHandled event ${event.eventId}")
            }
        } catch (t: Throwable) {
            Timber.e(t, "## Should not happen ")
        }
    }

    // OPT OUT serer aggregation until API mature enough
    private val SHOULD_HANDLE_SERVER_AGREGGATION = false // should be true to work with e2e

    private fun handleReplace(
            realm: Realm,
            event: Event,
            content: MessageContent,
            roomId: String,
            isLocalEcho: Boolean,
            relatedEventId: String? = null
    ) {
        val eventId = event.eventId ?: return
        val targetEventId = relatedEventId ?: content.relatesTo?.eventId ?: return
        val newContent = content.newContent ?: return

        // Check that the sender is the same
        val editedEvent = EventEntity.where(realm, targetEventId).findFirst()
        if (editedEvent == null) {
            // We do not know yet about the edited event
        } else if (editedEvent.sender != event.senderId) {
            // Edited by someone else, ignore
            Timber.w("Ignore edition by someone else")
            return
        }

        // ok, this is a replace
        val eventAnnotationsSummaryEntity = EventAnnotationsSummaryEntity.getOrCreate(realm, roomId, targetEventId)

        // we have it
        val existingSummary = eventAnnotationsSummaryEntity.editSummary
        if (existingSummary == null) {
            Timber.v("###REPLACE new edit summary for $targetEventId, creating one (localEcho:$isLocalEcho)")
            // create the edit summary
            eventAnnotationsSummaryEntity.editSummary = realm.createObject(EditAggregatedSummaryEntity::class.java)
                    .also { editSummary ->
                        editSummary.editions.add(
                                EditionOfEvent(
                                        senderId = event.senderId ?: "",
                                        eventId = event.eventId,
                                        content = ContentMapper.map(newContent),
                                        timestamp = if (isLocalEcho) 0 else event.originServerTs ?: 0,
                                        isLocalEcho = isLocalEcho
                                )
                        )
                    }
        } else {
            if (existingSummary.editions.any { it.eventId == eventId }) {
                // ignore this event, we already know it (??)
                Timber.v("###REPLACE ignoring event for summary, it's known $eventId")
                return
            }

            val txId = event.unsignedData?.transactionId
            // is it a remote echo?
            if (!isLocalEcho && existingSummary.editions.any { it.eventId == txId }) {
                // ok it has already been managed
                Timber.v("###REPLACE Receiving remote echo of edit (edit already done)")
                existingSummary.editions.firstOrNull { it.eventId == txId }?.let {
                    it.eventId = event.eventId
                    it.timestamp = event.originServerTs ?: clock.epochMillis()
                    it.isLocalEcho = false
                }
            } else {
                Timber.v("###REPLACE Computing aggregated edit summary (isLocalEcho:$isLocalEcho)")
                existingSummary.editions.add(
                        EditionOfEvent(
                                senderId = event.senderId ?: "",
                                eventId = event.eventId,
                                content = ContentMapper.map(newContent),
                                timestamp = if (isLocalEcho) {
                                    clock.epochMillis()
                                } else {
                                    // Do not take local echo originServerTs here, could mess up ordering (keep old ts)
                                    event.originServerTs ?: clock.epochMillis()
                                },
                                isLocalEcho = isLocalEcho
                        )
                )
            }
        }

        if (event.getClearType() in EventType.POLL_START) {
            pollAggregationProcessor.handlePollStartEvent(realm, event)
        }

        if (!isLocalEcho) {
            val replaceEvent = TimelineEventEntity
                    .where(realm, roomId, eventId)
                    .equalTo(TimelineEventEntityFields.OWNED_BY_THREAD_CHUNK, false)
                    .findFirst()
            handleThreadSummaryEdition(editedEvent, replaceEvent, existingSummary?.editions)
        }
    }

    /**
     * Check if the edition is on the latest thread event, and update it accordingly.
     * @param editedEvent The event that will be changed
     * @param replaceEvent The new event
     * @param editions list of edition of event
     */
    private fun handleThreadSummaryEdition(
            editedEvent: EventEntity?,
            replaceEvent: TimelineEventEntity?,
            editions: List<EditionOfEvent>?
    ) {
        replaceEvent ?: return
        editedEvent ?: return
        editedEvent.findRootThreadEvent()?.apply {
            val threadSummaryEventId = threadSummaryLatestMessage?.eventId
            if (editedEvent.eventId == threadSummaryEventId || editions?.any { it.eventId == threadSummaryEventId } == true) {
                // The edition is for the latest event or for any event replaced, this is to handle multiple
                // edits of the same latest event
                threadSummaryLatestMessage = replaceEvent
            }
        }
    }

    private fun getPowerLevelsHelper(roomId: String): PowerLevelsHelper? {
        return stateEventDataSource.getStateEvent(roomId, EventType.STATE_ROOM_POWER_LEVELS, QueryStringValue.NoCondition)
                ?.content?.toModel<PowerLevelsContent>()
                ?.let { PowerLevelsHelper(it) }
    }

    private fun handleInitialAggregatedRelations(
            realm: Realm,
            event: Event,
            roomId: String,
            aggregation: AggregatedAnnotation
    ) {
        if (SHOULD_HANDLE_SERVER_AGREGGATION) {
            aggregation.chunk?.forEach {
                if (it.type == EventType.REACTION) {
                    val eventId = event.eventId ?: ""
                    val existing = EventAnnotationsSummaryEntity.where(realm, roomId, eventId).findFirst()
                    if (existing == null) {
                        val eventSummary = EventAnnotationsSummaryEntity.create(realm, roomId, eventId)
                        val sum = realm.createObject(ReactionAggregatedSummaryEntity::class.java)
                        sum.key = it.key
                        sum.firstTimestamp = event.originServerTs
                                ?: 0 // TODO how to maintain order?
                        sum.count = it.count
                        eventSummary.reactionsSummary.add(sum)
                    } else {
                        // TODO how to handle that
                    }
                }
            }
        }
    }

    private fun handleReaction(
            realm: Realm,
            event: Event,
            roomId: String,
            isLocalEcho: Boolean
    ) {
        val content = event.content.toModel<ReactionContent>()
        if (content == null) {
            Timber.e("Malformed reaction content ${event.content}")
            return
        }
        // rel_type must be m.annotation
        if (RelationType.ANNOTATION == content.relatesTo?.type) {
            val reaction = content.relatesTo.key
            val relatedEventID = content.relatesTo.eventId
            val reactionEventId = event.eventId
            Timber.v("Reaction $reactionEventId relates to $relatedEventID")
            val eventSummary = EventAnnotationsSummaryEntity.getOrCreate(realm, roomId, relatedEventID)

            var sum = eventSummary.reactionsSummary.find { it.key == reaction }
            val txId = event.unsignedData?.transactionId
            if (isLocalEcho && txId.isNullOrBlank()) {
                Timber.w("Received a local echo with no transaction ID")
            }
            if (sum == null) {
                sum = realm.createObject(ReactionAggregatedSummaryEntity::class.java)
                sum.key = reaction
                sum.firstTimestamp = event.originServerTs ?: 0
                if (isLocalEcho) {
                    Timber.v("Adding local echo reaction")
                    sum.sourceLocalEcho.add(txId)
                    sum.count = 1
                } else {
                    Timber.v("Adding synced reaction")
                    sum.count = 1
                    sum.sourceEvents.add(reactionEventId)
                }
                sum.addedByMe = sum.addedByMe || (userId == event.senderId)
                eventSummary.reactionsSummary.add(sum)
            } else {
                // is this a known event (is possible? pagination?)
                if (!sum.sourceEvents.contains(reactionEventId)) {
                    // check if it's not the sync of a local echo
                    if (!isLocalEcho && sum.sourceLocalEcho.contains(txId)) {
                        // ok it has already been counted, just sync the list, do not touch count
                        Timber.v("Ignoring synced of local echo for reaction")
                        sum.sourceLocalEcho.remove(txId)
                        sum.sourceEvents.add(reactionEventId)
                    } else {
                        sum.count += 1
                        if (isLocalEcho) {
                            Timber.v("Adding local echo reaction")
                            sum.sourceLocalEcho.add(txId)
                        } else {
                            Timber.v("Adding synced reaction")
                            sum.sourceEvents.add(reactionEventId)
                        }

                        sum.addedByMe = sum.addedByMe || (userId == event.senderId)
                    }
                }
            }
        } else {
            Timber.e("Unknown relation type ${content.relatesTo?.type} for event ${event.eventId}")
        }
    }

    /**
     * Called when an event is deleted.
     */
    private fun handleRedactionOfReplace(
            realm: Realm,
            redacted: EventEntity,
            relatedEventId: String
    ) {
        Timber.d("Handle redaction of m.replace")
        val eventSummary = EventAnnotationsSummaryEntity.where(realm, redacted.roomId, relatedEventId).findFirst()
        if (eventSummary == null) {
            Timber.w("Redaction of a replace targeting an unknown event $relatedEventId")
            return
        }
        val sourceToDiscard = eventSummary.editSummary?.editions?.firstOrNull { it.eventId == redacted.eventId }
        if (sourceToDiscard == null) {
            Timber.w("Redaction of a replace that was not known in aggregation $sourceToDiscard")
            return
        }
        // Need to remove this event from the edition list
        sourceToDiscard.deleteFromRealm()
    }

    private fun handleReactionRedact(
            realm: Realm,
            eventToPrune: EventEntity
    ) {
        Timber.v("REDACTION of reaction ${eventToPrune.eventId}")
        // delete a reaction, need to update the annotation summary if any
        val reactionContent: ReactionContent = EventMapper.map(eventToPrune).content.toModel() ?: return
        val eventThatWasReacted = reactionContent.relatesTo?.eventId ?: return

        val reactionKey = reactionContent.relatesTo.key
        Timber.v("REMOVE reaction for key $reactionKey")
        val summary = EventAnnotationsSummaryEntity.where(realm, eventToPrune.roomId, eventThatWasReacted).findFirst()
        if (summary != null) {
            summary.reactionsSummary.where()
                    .equalTo(ReactionAggregatedSummaryEntityFields.KEY, reactionKey)
                    .findFirst()?.let { aggregation ->
                        Timber.v("Find summary for key with  ${aggregation.sourceEvents.size} known reactions (count:${aggregation.count})")
                        Timber.v("Known reactions  ${aggregation.sourceEvents.joinToString(",")}")
                        if (aggregation.sourceEvents.contains(eventToPrune.eventId)) {
                            Timber.v("REMOVE reaction for key $reactionKey")
                            aggregation.sourceEvents.remove(eventToPrune.eventId)
                            Timber.v("Known reactions after  ${aggregation.sourceEvents.joinToString(",")}")
                            aggregation.count = aggregation.count - 1
                            if (eventToPrune.sender == userId) {
                                // Was it a redact on my reaction?
                                aggregation.addedByMe = false
                            }
                            if (aggregation.count == 0) {
                                // delete!
                                aggregation.deleteFromRealm()
                            }
                        } else {
                            Timber.e("## Cannot remove summary from count, corresponding reaction ${eventToPrune.eventId} is not known")
                        }
                    }
        } else {
            Timber.e("## Cannot find summary for key $reactionKey")
        }
    }

    private fun handleVerification(realm: Realm, event: Event, roomId: String, isLocalEcho: Boolean, relatedEventId: String) {
        val eventSummary = EventAnnotationsSummaryEntity.getOrCreate(realm, roomId, relatedEventId)

        val verifSummary = eventSummary.referencesSummaryEntity
                ?: ReferencesAggregatedSummaryEntity.create(realm, relatedEventId).also {
                    eventSummary.referencesSummaryEntity = it
                }

        val txId = event.unsignedData?.transactionId

        if (!isLocalEcho && verifSummary.sourceLocalEcho.contains(txId)) {
            // ok it has already been handled
        } else {
            ContentMapper.map(verifSummary.content)?.toModel<ReferencesAggregatedContent>()
            var data = ContentMapper.map(verifSummary.content)?.toModel<ReferencesAggregatedContent>()
                    ?: ReferencesAggregatedContent(VerificationState.REQUEST)
            // TODO ignore invalid messages? e.g a START after a CANCEL?
            // i.e. never change state if already canceled/done
            val currentState = data.verificationState
            val newState = when (event.getClearType()) {
                EventType.KEY_VERIFICATION_START,
                EventType.KEY_VERIFICATION_ACCEPT,
                EventType.KEY_VERIFICATION_READY,
                EventType.KEY_VERIFICATION_KEY,
                EventType.KEY_VERIFICATION_MAC    -> currentState.toState(VerificationState.WAITING)
                EventType.KEY_VERIFICATION_CANCEL -> currentState.toState(
                        if (event.senderId == userId) {
                            VerificationState.CANCELED_BY_ME
                        } else {
                            VerificationState.CANCELED_BY_OTHER
                        }
                )
                EventType.KEY_VERIFICATION_DONE   -> currentState.toState(VerificationState.DONE)
                else                              -> VerificationState.REQUEST
            }

            data = data.copy(verificationState = newState)
            verifSummary.content = ContentMapper.map(data.toContent())
        }

        if (isLocalEcho) {
            verifSummary.sourceLocalEcho.add(event.eventId)
        } else {
            verifSummary.sourceLocalEcho.remove(txId)
            verifSummary.sourceEvents.add(event.eventId)
        }
    }

    private fun handleBeaconLocationData(event: Event, realm: Realm, roomId: String, isLocalEcho: Boolean) {
        event.getClearContent().toModel<MessageBeaconLocationDataContent>(catchError = true)?.let {
            liveLocationAggregationProcessor.handleBeaconLocationData(
                    realm,
                    event,
                    it,
                    roomId,
                    event.getRelationContent()?.eventId,
                    isLocalEcho
            )
        }
    }
}
