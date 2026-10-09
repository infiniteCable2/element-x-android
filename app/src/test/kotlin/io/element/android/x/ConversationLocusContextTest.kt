/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.x

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID_2
import io.element.android.libraries.matrix.test.A_THREAD_ID
import io.element.android.libraries.push.api.notifications.bubbles.BubbleRoom
import io.element.android.services.appnavstate.api.NavigationState
import org.junit.Test

class ConversationLocusContextTest {
    private val session = NavigationState.Session("session", A_SESSION_ID)
    private val room = NavigationState.Room("room", A_ROOM_ID, session)

    @Test
    fun `a visible room uses its bubble locus`() {
        assertThat(room.conversationLocusId()).isEqualTo(BubbleRoom(A_SESSION_ID, A_ROOM_ID).locusId)
    }

    @Test
    fun `a thread keeps the parent room locus`() {
        val thread = NavigationState.Thread("thread", A_THREAD_ID, room)
        assertThat(thread.conversationLocusId()).isEqualTo(room.conversationLocusId())
    }

    @Test
    fun `leaving a room explicitly replaces its locus with a non-chat context`() {
        val loci = listOf(room, session, room).map { it.conversationLocusId() }
        assertThat(loci[0]).isNotEqualTo(loci[1])
        assertThat(loci[1]).isNotEmpty()
        assertThat(loci[2]).isEqualTo(loci[0])
    }

    @Test
    fun `root and session contexts do not match conversation bubbles`() {
        val overview = NavigationState.Root.conversationLocusId()
        assertThat(session.conversationLocusId()).isEqualTo(overview)
        assertThat(overview).isNotEqualTo(BubbleRoom(A_SESSION_ID, A_ROOM_ID).locusId)
        assertThat(overview).isNotEqualTo(BubbleRoom(A_SESSION_ID_2, A_ROOM_ID_2).locusId)
    }

    @Test
    fun `changing rooms updates the visible locus`() {
        val otherRoom = NavigationState.Room("other-room", A_ROOM_ID_2, session)
        assertThat(otherRoom.conversationLocusId()).isNotEqualTo(room.conversationLocusId())
    }

    @Test
    fun `the same room on a different account has a different locus`() {
        val otherSession = NavigationState.Session("other-session", A_SESSION_ID_2)
        val otherRoom = NavigationState.Room("other-room", A_ROOM_ID, otherSession)
        assertThat(otherRoom.conversationLocusId()).isNotEqualTo(room.conversationLocusId())
    }
}
