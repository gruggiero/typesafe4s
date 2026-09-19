package typesafe4s.integration

import scala.concurrent.{ExecutionContext, Future}

import kyo.compat.*
import munit.FunSuite

import typesafe4s.client.internal.CarrierProbe

/**
  * Ring 5 — cross-backend parity, in its smallest possible form.
  *
  * This suite is written ONCE, against `CIO`, and is recompiled and executed for EVERY backend row. It is not a test of the SDK (there is
  * no SDK yet); it is a test that the carrier genuinely RUNS on each backend, so that later behavioural suites written here mean something.
  *
  * A test that exists only in one row proves nothing about the other four.
  */
final class CarrierParitySuite extends FunSuite {

  // munit runs Future-returning tests on this context; the carrier's `unsafeRun` hands back a Future on every backend.
  private given ExecutionContext = munitExecutionContext

  test("the carrier lifts a pure value") {
    val result: Future[Int] = CarrierProbe.value(42).unsafeRun
    result.map(value => assertEquals(value, 42))
  }

  test("the carrier composes") {
    val result: Future[Int] = CarrierProbe.mapped(1).unsafeRun
    result.map(value => assertEquals(value, 2))
  }

  test("the carrier carries failure") {
    val boom   = new RuntimeException("boom")
    val result = CarrierProbe.failed(boom).unsafeRun.failed
    result.map(error => assertEquals(error.getMessage, "boom"))
  }
}
