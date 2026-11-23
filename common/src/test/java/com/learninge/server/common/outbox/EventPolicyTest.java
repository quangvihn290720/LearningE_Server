package com.learninge.server.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.learninge.proto.internal.v1.Event;
import com.learninge.proto.internal.v1.UserDeleted;
import com.learninge.proto.internal.v1.XpRecorded;
import org.junit.jupiter.api.Test;

class EventPolicyTest {
  private static final String USER = "11111111-2222-4333-8444-555555555555";
  private static final String HMAC = "0123456789abcdef0123456789abcdef";

  private static Event.Builder xp(int awarded) {
    return Event.newBuilder()
        .setEventId(USER)
        .setType("xp.recorded")
        .setSource("sync")
        .setXpRecorded(
            XpRecorded.newBuilder().setUserId(USER).setXpUuid(USER).setXpAwarded(awarded));
  }

  @Test
  void acceptsValidEvent() {
    assertThat(EventPolicy.violation(xp(500).build(), Destination.GAMIFICATION)).isEmpty();
  }

  @Test
  void rejectsForgedSourceDestinationAndAmounts() {
    assertThat(EventPolicy.violation(xp(1).setSource("auth").build(), Destination.GAMIFICATION))
        .contains("sai nguồn phát");
    assertThat(EventPolicy.violation(xp(1).build(), Destination.SYNC)).contains("sai đích nhận");
    assertThat(EventPolicy.violation(xp(9999).build(), Destination.GAMIFICATION)).isPresent();
    assertThat(EventPolicy.violation(xp(-1).build(), Destination.GAMIFICATION)).isPresent();
    assertThat(EventPolicy.violation(xp(1).setType("user.deleted").build(), Destination.INSIGHT))
        .isPresent();
    assertThat(EventPolicy.violation(xp(1).setType("x").build(), Destination.INSIGHT)).isPresent();
  }

  @Test
  void insightMustNotReceiveUserId() {
    Event.Builder event =
        Event.newBuilder().setEventId(USER).setType("user.deleted").setSource("auth");
    assertThat(
            EventPolicy.violation(
                event.setUserDeleted(UserDeleted.newBuilder().setUserHmac(HMAC)).build(),
                Destination.INSIGHT))
        .isEmpty();
    assertThat(
            EventPolicy.violation(
                event
                    .setUserDeleted(UserDeleted.newBuilder().setUserHmac(HMAC).setUserId(USER))
                    .build(),
                Destination.INSIGHT))
        .isPresent();
  }
}
