package org.kaloz.gpio

import java.util.UUID

import akka.actor.{ActorLogging, Props}
import akka.event.LoggingReceive
import akka.persistence.{PersistentActor, SnapshotOffer}
import org.joda.time.DateTime
import org.kaloz.gpio.ReactionFlowControllerActor.StartTestFlow
import org.kaloz.gpio.SessionHandlerActor.{ReactionTestResultArrivedEvent, SaveReactionTestResultCmd, TestAbortedEvent}
import org.kaloz.gpio.common.BcmPinConversions.GPIOPinConversion
import org.kaloz.gpio.common.BcmPins.{BCM_24, BCM_25}
import org.kaloz.gpio.common.PinController

class SessionHandlerActor(pinController: PinController, reactionLedPulseLength: Int, reactionCorrectionFactor: Int, reactionThreshold: Int, numberOfWinners: Int) extends PersistentActor with ActorLogging {

  override val persistenceId: String = "sessionHandlerActor"

  val startButton = pinController.digitalInputPin(BCM_25("Start"))
  val resultButton = pinController.digitalInputPin(BCM_24("Result"))
  val reactionFlowControllerActor = context.actorOf(ReactionFlowControllerActor.props(pinController, reactionLedPulseLength, reactionCorrectionFactor, reactionThreshold), "reactionFlowControllerActor")

  var reactionTestState = ReactionTestState()

  initializeDefaultButtons()

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, offeredSnapshot: ReactionTestState) =>
      reactionTestState = offeredSnapshot
      log.info(s"Snapshot has been loaded with ${reactionTestState.testResults.size} test results!")
  }

  def updateState(evt: ReactionTestResultArrivedEvent): Unit = {
    reactionTestState = reactionTestState.update(evt.testResult)
    saveSnapshot(reactionTestState)
    log.info(s"Result for ${evt.testResult.user.userName} has been persested!")
    log.info(s"${evt.testResult.result.iterations} iterations - ${evt.testResult.result.avg} ms avg response time - ${evt.testResult.result.std} std")
    log.info(s"Position with the best of the user is ${reactionTestState.positionOf(evt.testResult.user)}")

    initializeDefaultButtons()
  }

  override def receiveCommand: Receive = LoggingReceive {
    case SaveReactionTestResultCmd(testResult) =>
      persist(ReactionTestResultArrivedEvent(testResult))(updateState)
    case TestAbortedEvent(userOption) =>
      userOption.fold(log.info(s"Test is aborted without user data..")) { user => log.info(s"Test is aborted for user $user") }
      initializeDefaultButtons()
  }

  private def initializeDefaultButtons() = {
    log.info("Waiting test to be started!!")
    startButton.addStateChangeFallEventListener { event =>
      startButton.removeAllListeners()
      resultButton.removeAllListeners()
      reactionFlowControllerActor ! StartTestFlow
    }
    resultButton.addStateChangeFallEventListener { event =>
      log.info(s"Results!! ${reactionTestState.take(numberOfWinners)}")
      pinController.shutdown()
      context.system.terminate()
    }
  }
}

object SessionHandlerActor {
  def props(pinController: PinController, reactionLedPulseLength: Int, reactionCorrectionFactor: Int, reactionThreshold: Int, numberOfWinners: Int) = Props(classOf[SessionHandlerActor], pinController, reactionLedPulseLength, reactionCorrectionFactor, reactionThreshold, numberOfWinners)

  case class SaveReactionTestResultCmd(testResult: TestResult)

  case class TestAbortedEvent(user: Option[User])

  case class ReactionTestResultArrivedEvent(testResult: TestResult)

}

case class ReactionTestState(testResults: List[TestResult] = List.empty) {
  def update(testResult: TestResult) = copy(testResult :: testResults)

  def take(numberOfWinners: Int) = testResults.groupBy(_.user).values.map(_.sorted.head).toList.sorted.take(numberOfWinners).zipWithIndex.map(_.swap)

  def bestOf(user: User) = testResults.filter(_.user == user).sorted.head

  def positionOf(user: User) = testResults.groupBy(_.user).values.map(_.sorted.head).toList.sorted.zipWithIndex.filter(item => item._1.user == user).head.swap
}

case class TestResult(user: User, result: Result) extends Ordered[TestResult] {
  def compare(that: TestResult): Int = result compare that.result
}

case class User(userName: String, email: String, desc: String, phone: Option[String] = None)

case class Result(id: String = UUID.randomUUID().toString, startTime: DateTime = DateTime.now(), iterations: Int, avg: Int, std: Double) extends Ordered[Result] {
  def compare(that: Result): Int =
    (iterations compare that.iterations) match {
      case 0 =>
        (avg compare that.avg) match {
          case 0 => std compare that.std
          case c => c
        }
      case c => -c
    }
}
