// Copyright (c) 2018 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package tests

import ffstest.FTest
import cats.effect.IO
import cats.effect.Resource
import cats.implicits._
import scala.concurrent.duration._
import skunk.util.Pool
import cats.effect.concurrent.Ref
import skunk.util.Pool.ResourceLeak
import cats.effect.concurrent.Deferred

case object PoolTest extends FTest {

  case class UserFailure() extends Exception("user failure")
  case class AllocFailure() extends Exception("allocation failure")
  case class FreeFailure() extends Exception("free failure")
  case class ResetFailure() extends Exception("reset failure")

  val ints: IO[Resource[IO, Int]] =
    Ref[IO].of(1).map { ref =>
      val next = ref.modify(n => (n + 1, n.pure[IO])).flatten
      Resource.make(next)(_ => IO.unit)
    }

  // list of computations into computation that yields results one by one
  def yielding[A](fas: IO[A]*): IO[IO[A]] =
    Ref[IO].of(fas.toList).map { ref =>
      ref.modify {
        case Nil       => (Nil, IO.raiseError(new Exception("No more values!")))
        case fa :: fas => (fas, fa)
      } .flatten
    }

  def resourceYielding[A](fas: IO[A]*): IO[Resource[IO, A]] =
    yielding(fas: _*).map(Resource.make(_)(_ => IO.unit))

  // This test leaks
  test("error in alloc is rethrown to caller (immediate)") {
    val rsrc = Resource.make(IO.raiseError[String](AllocFailure()))(_ => IO.unit)
    val pool = Pool.of(rsrc, 42)(_ => true.pure[IO])
    pool.use(_.use(_ => IO.unit)).assertFailsWith[AllocFailure].void
  }

  test("error in alloc is rethrown to caller (deferral completion following errored cleanup)") {
    resourceYielding(IO(1), IO.raiseError(AllocFailure())).flatMap { r =>
      val p = Pool.of(r, 1)(_ => IO.raiseError(ResetFailure()))
      p.use { r =>
        for {
          d  <- Deferred[IO, Unit]
          f1 <- r.use(n => assertEqual("n should be 1", n, 1) *> d.get).assertFailsWith[ResetFailure].start
          f2 <- r.use(_ => fail[Int]("should never get here")).assertFailsWith[AllocFailure].start
          _  <- d.complete(())
          _  <- f1.join
          _  <- f2.join
        } yield ()
      }
    }
  }

  test("error in alloc is rethrown to caller (deferral completion following failed cleanup)") {
    resourceYielding(IO(1), IO.raiseError(AllocFailure())).flatMap { r =>
      val p = Pool.of(r, 1)(_ => false.pure[IO])
      p.use { r =>
        for {
          d  <- Deferred[IO, Unit]
          f1 <- r.use(n => assertEqual("n should be 1", n, 1) *> d.get).start
          f2 <- r.use(_ => fail[Int]("should never get here")).assertFailsWith[AllocFailure].start
          _  <- d.complete(())
          _  <- f1.join
          _  <- f2.join
        } yield ()
      }
    }
  }

  test("provoke dangling deferral cancellation") {
    fail("not implemented")
  }

  test("error in free is rethrown to caller") {
    val rsrc = Resource.make("foo".pure[IO])(_ => IO.raiseError(FreeFailure()))
    val pool = Pool.of(rsrc, 42)(_ => true.pure[IO])
    pool.use(_.use(_ => IO.unit)).assertFailsWith[FreeFailure]
  }

  test("error in reset is rethrown to caller") {
    val rsrc = Resource.make("foo".pure[IO])(_ => IO.unit)
    val pool = Pool.of(rsrc, 42)(_ => IO.raiseError(ResetFailure()))
    pool.use(_.use(_ => IO.unit)).assertFailsWith[ResetFailure]
  }

  test("reuse on serial access") {
    ints.map(Pool.of(_, 3)(_ => true.pure[IO])).flatMap { factory =>
      factory.use { pool =>
        pool.use { n =>
          assertEqual("first num should be 1", n, 1)
        } *>
        pool.use { n =>
          assertEqual("we should get it again", n, 1)
        }
      }
    }
  }

  test("allocation on nested access") {
    ints.map(Pool.of(_, 3)(_ => true.pure[IO])).flatMap { factory =>
      factory.use { pool =>
        pool.use { n =>
          assertEqual("first num should be 1", n, 1) *>
          pool.use { n =>
            assertEqual("but this one should be 2", n, 2)
          } *>
          pool.use { n =>
            assertEqual("and again", n, 2)
          }
        }
      }
    }
  }

  test("allocated resource can cause a leak, which will be detected on finalization") {
    ints.map(Pool.of(_, 3)(_ => true.pure[IO])).flatMap { factory =>
      factory.use { pool =>
        pool.allocated
      } .assertFailsWith[ResourceLeak].flatMap {
        case ResourceLeak(expected, actual) =>
          assert("expected 1 leakage", expected - actual == 1)
      }
    }
  }

  test("unmoored fiber can cause a leak, which will be detected on finalization") {
    ints.map(Pool.of(_, 3)(_ => true.pure[IO])).flatMap { factory =>
      factory.use { pool =>
        pool.use(_ => IO.never).start *>
        IO.sleep(100.milli) // ensure that the fiber has a chance to run
      } .assertFailsWith[ResourceLeak].flatMap {
        case ResourceLeak(expected, actual) =>
          assert("expected 1 leakage", expected - actual == 1)
      }
    }
  }

  // Concurrency tests below. These are nondeterministic and need a lot of exercise.

  val PoolSize = 10
  val ConcurrentTasks = 500

  test("progress and safety with many fibers") {
    ints.map(Pool.of(_, PoolSize)(_ => true.pure[IO])).flatMap { factory =>
      (1 to ConcurrentTasks).toList.parTraverse_{ _ =>
        factory.use { p =>
          p.use { _ =>
            for {
              t <- IO(util.Random.nextInt % 100)
              _ <- IO.sleep(t.milliseconds)
            } yield ()
          }
        }
      }
    }
  }

  test("progress and safety with many fibers and cancellation") {
    ints.map(Pool.of(_, PoolSize)(_ => true.pure[IO])).flatMap { factory =>
      factory.use { pool =>
        (1 to ConcurrentTasks).toList.parTraverse_{_ =>
          for {
            t <- IO(util.Random.nextInt % 100)
            f <- pool.use(_ => IO.sleep(t.milliseconds)).start
            _ <- if (t > 50) f.join else f.cancel
          } yield ()
        }
      }
    }
  }

  test("progress and safety with many fibers and user failures") {
    ints.map(Pool.of(_, PoolSize)(_ => true.pure[IO])).flatMap { factory =>
      factory.use { pool =>
        (1 to ConcurrentTasks).toList.parTraverse_{ _ =>
          pool.use { _ =>
            for {
              t <- IO(util.Random.nextInt % 100)
              _ <- IO.sleep(t.milliseconds)
              _ <- IO.raiseError(UserFailure()).whenA(t < 50)
            } yield ()
          } .attempt // swallow errors so we don't fail fast
        }
      }
    }
  }

  test("progress and safety with many fibers and allocation failures") {
    val alloc = IO(util.Random.nextBoolean).flatMap {
      case true  => IO.unit
      case false => IO.raiseError(AllocFailure())
    }
    val rsrc = Resource.make(alloc)(_ => IO.unit)
    Pool.of(rsrc, PoolSize)(_ => true.pure[IO]).use { pool =>
      (1 to ConcurrentTasks).toList.parTraverse_{ _ =>
        pool.use { _ =>
          IO.unit
        } .attempt
      }
    }
  }

  test("progress and safety with many fibers and freeing failures") {
    val free = IO(util.Random.nextBoolean).flatMap {
      case true  => IO.unit
      case false => IO.raiseError(FreeFailure())
    }
    val rsrc  = Resource.make(IO.unit)(_ => free)
    Pool.of(rsrc, PoolSize)(_ => true.pure[IO]).use { pool =>
      (1 to ConcurrentTasks).toList.parTraverse_{ _ =>
        pool.use { _ =>
          IO.unit
        } .attempt
      }
    } .handleErrorWith {
      // cleanup here may raise an exception, so we need to handle that
      case FreeFailure() => IO.unit
    }
  }

  test("progress and safety with many fibers and reset failures") {
    val reset = IO(util.Random.nextInt(3)).flatMap {
      case 0 => true.pure[IO]
      case 1 => false.pure[IO]
      case 2 => IO.raiseError(ResetFailure())
    }
    val rsrc  = Resource.make(IO.unit)(_ => IO.unit)
    Pool.of(rsrc, PoolSize)(_ => reset).use { pool =>
      (1 to ConcurrentTasks).toList.parTraverse_{ _ =>
        pool.use { _ =>
          IO.unit
        } handleErrorWith {
          case ResetFailure() => IO.unit
        }
      }
    }
  }

}