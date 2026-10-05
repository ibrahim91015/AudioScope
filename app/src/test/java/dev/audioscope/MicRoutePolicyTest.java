package dev.audioscope;

import static org.junit.Assert.*;

import org.junit.Test;

public class MicRoutePolicyTest {
  @Test
  public void phoneAndTelephonyRoutesAreNotExternal() {
    assertTrue(MicRoutePolicy.acceptPhone(false, 15));
    assertTrue(MicRoutePolicy.acceptPhone(false, 18));
    assertFalse(MicRoutePolicy.external(18));
  }

  @Test
  public void flexiblePhoneAcceptsNonBluetoothMicsButNotRemoteSubmix() {
    assertTrue(MicRoutePolicy.acceptPhone(true, 0));
    assertTrue(MicRoutePolicy.acceptPhone(true, 22));
    assertFalse(MicRoutePolicy.acceptPhone(false, 22));
    for (int type : new int[] {7, 8, 23, 26, 27, 30, 25})
      assertFalse(MicRoutePolicy.acceptPhone(true, type));
  }
}
