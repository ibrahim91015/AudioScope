package dev.audioscope;

import static org.junit.Assert.*;

import org.junit.Test;

public class MicRoutePolicyTest {
  @Test
  public void communicationInputFollowsSystemWhilePhoneInputsStillRejectBluetooth() {
    assertTrue(Source.get("communication_input").systemSelectedMic());
    assertFalse(Source.get("mic").systemSelectedMic());
    assertFalse(Source.get("any_phone_mic").systemSelectedMic());
    for (int type : new int[] {0, 3, 7, 15, 18, 22, 26}) {
      assertTrue(MicRoutePolicy.acceptInput(true, false, type));
    }
    assertFalse(MicRoutePolicy.acceptInput(false, false, 7));
    assertFalse(MicRoutePolicy.acceptInput(false, true, 7));
  }

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
