/*
 * Copyright 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.home.room.typing

import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import javax.inject.Inject

class TypingHelper @Inject constructor(private val stringProvider: StringProvider) {

    /**
     * Returns a human readable String of currently typing users in the room (excluding yourself).
     */
    fun getTypingMessage(typingUsers: List<SenderInfo>): String {
        return when {
            typingUsers.isEmpty() ->
                ""
            typingUsers.size == 1 ->
                stringProvider.getString(R.string.room_one_user_is_typing, typingUsers[0].disambiguatedDisplayName)
            typingUsers.size == 2 ->
                stringProvider.getString(
                        R.string.room_two_users_are_typing,
                        typingUsers[0].disambiguatedDisplayName,
                        typingUsers[1].disambiguatedDisplayName
                )
            else                  ->
                stringProvider.getString(
                        R.string.room_many_users_are_typing,
                        typingUsers[0].disambiguatedDisplayName,
                        typingUsers[1].disambiguatedDisplayName
                )
        }
    }

    fun getNotificationTypingMessage(typingUsers: List<SenderInfo>): String {
        return when {
            typingUsers.isEmpty() -> ""
            typingUsers.size == 1 -> typingUsers[0].disambiguatedDisplayName
            typingUsers.size == 2 -> stringProvider.getString(
                    R.string.room_notification_two_users_are_typing,
                    typingUsers[0].disambiguatedDisplayName, typingUsers[1].disambiguatedDisplayName
            )
            else                  -> stringProvider.getString(
                    R.string.room_notification_more_than_two_users_are_typing,
                    typingUsers[0].disambiguatedDisplayName, typingUsers[1].disambiguatedDisplayName
            )
        }
    }
}
