package org.kaloz.gpio

import org.kaloz.gpio.common.PinController

trait GpioAppDI extends GpioAppConfig {

  val pinController = new PinController()

  val reactionController = new ReactionFlowController(pinController, reactionLedPulseLength, reactionCorrectionFactor, reactionThreshold)
  val sessionHandler = new SessionHandler(pinController, reactionController)
}
