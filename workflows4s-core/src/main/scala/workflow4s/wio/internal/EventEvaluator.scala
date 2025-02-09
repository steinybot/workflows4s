package workflow4s.wio.internal

import cats.data.Ior
import cats.implicits.{catsSyntaxEitherId, catsSyntaxOptionId}
import workflow4s.wio.Interpreter.EventResponse
import workflow4s.wio.NextWfState.NewBehaviour
import workflow4s.wio.WIO.Timer.DurationSource
import workflow4s.wio.{Interpreter, NextWfState, Visitor, WCEvent, WCState, WIO, WorkflowContext}

object EventEvaluator {

  def handleEvent[Ctx <: WorkflowContext, StIn <: WCState[Ctx]](
      event: WCEvent[Ctx],
      wio: WIO[StIn, Nothing, WCState[Ctx], Ctx],
      state: StIn,
      interpreter: Interpreter,
  ): EventResponse[Ctx] = {
    val visitor = new EventVisitor(wio, event, state, state)
    visitor.run
      .map(wf => wf.toActiveWorkflow(interpreter))
      .map(EventResponse.Ok(_))
      .getOrElse(EventResponse.UnexpectedEvent())
  }

  private class EventVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      event: WCEvent[Ctx],
      state: In,
      initialState: WCState[Ctx],
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    type NewWf           = NextWfState[Ctx, Err, Out]
    override type Result = Option[NewWf]

    def doHandle[Evt](handler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Evt]): Result =
      handler
        .detect(event)
        .map(x => NextWfState.NewValue(handler.handle(state, x)))

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result                     = doHandle(wio.evtHandler.map(_._1))
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                   = doHandle(wio.evtHandler)
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          =
      recurse(wio.base, state, event).map(preserveFlatMap(wio, _))
    def onMap[In1, Out1 <: WCState[Ctx]](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                           =
      recurse(wio.base, wio.contramapInput(state), event).map(preserveMap(wio, _, state))
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                             = None
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                             = recurse(wio.base, state, event)
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result =
      recurse(wio.base, state, event).map((newWf: NextWfState[Ctx, ErrIn, Out]) => {
        val casted: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn }] // TODO casting
        applyHandleError(wio, casted, state)
      })
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           =
      recurse(wio.base, state, event).map((newWf: NextWfState[Ctx, ErrIn, Out]) => {
        val casted: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn }] // TODO casting
        applyHandleErrorWith(wio, casted, state)
      })
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             =
      recurse(wio.first, state, event).map(preserveAndThen(wio, _))
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                                               = None
    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result                                   =
      recurse(wio.current, state, event).map(applyLoop(wio, _))
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                               =
      selectMatching(wio, state).flatMap(recurse(_, state, event))
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState: WCState[InnerCtx] =
        wio.embedding
          .unconvertState(initialState)
          .getOrElse(
            wio.initialState(state),
          ) // TODO, this is not safe, we will use initial state if the state mapping is incorrect (not symetrical). This will be very hard for the user to diagnose.
      wio.embedding
        .unconvertEvent(event)
        .flatMap(convertedEvent => new EventVisitor(wio.inner, convertedEvent, state, newState).run)
        .map(convertResult(wio, _, state))
    }

    // will be problematic if we use the same event on both paths
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      def runBase: Result = recurse(wio.base, state, event)
        .map(preserveHandleInterruption(wio.interruption, _))

      wio.interruption.trigger match {
        case x @ WIO.HandleSignal(_, _, _, _) =>
          // if awaitTime proceeds, we switch the flow into there
          recurse(wio.interruption.finalWIO, initialState, event)
            .orElse(runBase)
        case x: WIO.Timer[Ctx, In, Err, Out]  =>
          runTimer(x) match {
            case Some(awaitTime) =>
              val mainFlowOut     = NewBehaviour(wio.base.transformInput[Any](_ => state), initialState.asRight)
              val newInterruption = WIO.Interruption(awaitTime, wio.interruption.buildFinal)
              preserveHandleInterruption(newInterruption, mainFlowOut).some
            case None            => runBase
          }
        case x @ WIO.AwaitingTime(_, _, _)    =>
          recurse(wio.interruption.finalWIO, initialState, event)
            .orElse(runBase)
      }
    }

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result = {
      runTimer(wio).map(result => {
        NextWfState.NewBehaviour(result, initialState.asRight)
      })
    }

    private def runTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Option[WIO.AwaitingTime[Ctx, In, Err, Out]] = {
      wio.startedEventHandler
        .detect(event)
        .map(started => {
          val releaseTime = wio.getReleaseTime(started, state)
          WIO.AwaitingTime(releaseTime, wio.onRelease, wio.releasedEventHandler)
        })
    }
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result                                 = doHandle(wio.releasedEventHandler)

    def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], s: I1, e: WCEvent[Ctx]): EventVisitor[Ctx, I1, E1, O1]#Result =
      new EventVisitor(wio, e, s, initialState).run

  }
}
