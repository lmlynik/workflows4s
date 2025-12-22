package workflows4s.example.docs

import workflows4s.example.docs.Context.WIO

object ForkExample {

  // start_match_condition
  val doA = WIO.pure(MyState(1)).autoNamed
  val doB = WIO.pure(MyState(2)).autoNamed

  val fork: WIO[MyState, Nothing, MyState] =
    WIO
      .fork[MyState]
      .matchCondition(_.counter > 0, "Is counter positive?")(
        onTrue = doA,
        onFalse = doB,
      )
  // end_match_condition

  // start_onsome
  val processLow    = WIO.pure(MyState(10)).named("Process Low Priority")
  val processMedium = WIO.pure(MyState(50)).named("Process Medium Priority")
  val processHigh   = WIO.pure(MyState(100)).named("Process High Priority")

  val multiwayFork: WIO[MyState, Nothing, MyState] =
    WIO
      .fork[MyState]
      .onSome((s: MyState) => Option.when(s.counter < 10)(s))(processLow, "Low?")
      .onSome((s: MyState) => Option.when(s.counter < 50)(s))(processMedium, "Medium?")
      .onSome((_: MyState) => Some(()))(processHigh, "High")
      .named("Priority Router")
      .done
  // end_onsome

}
