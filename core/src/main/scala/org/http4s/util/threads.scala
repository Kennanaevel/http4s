package org.http4s.util

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicLong
import org.log4s.getLogger

object threads {
  private[this] val log = getLogger

  final case class ThreadPriority(toInt: Int)
  case object ThreadPriority {
    val Min = ThreadPriority(Thread.MIN_PRIORITY)
    val Norm = ThreadPriority(Thread.NORM_PRIORITY)
    val Max = ThreadPriority(Thread.MAX_PRIORITY)
  }

  def threadFactory(
    name: Long => String = { l => s"http4s-$l" },
    daemon: Boolean = false,
    priority: ThreadPriority = ThreadPriority.Norm,
    uncaughtExceptionHandler: PartialFunction[(Thread, Throwable), Unit] = PartialFunction.empty,
    backingThreadFactory: ThreadFactory = Executors.defaultThreadFactory
  ): ThreadFactory =
    new ThreadFactory {
      val count = new AtomicLong(0)
      override def newThread(r: Runnable): Thread = {
        val thread = backingThreadFactory.newThread(r)
        thread.setName(name(count.getAndIncrement))
        thread.setDaemon(daemon)
        thread.setPriority(priority.toInt)
        thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
          override def uncaughtException(t: Thread, e: Throwable): Unit =
            (uncaughtExceptionHandler orElse fallthrough)((t, e))
          val fallthrough: PartialFunction[(Thread, Throwable), Unit] = {
            case (t: Thread, e: Throwable) =>
              Option(t.getThreadGroup)
                .getOrElse(Thread.getDefaultUncaughtExceptionHandler)
                .uncaughtException(t, e)
          }
        })
        thread
      }
    }

  @deprecated("Use newDaemonPool instead", "0.15.7")
  private[http4s] def newDefaultFixedThreadPool(n: Int, threadFactory: ThreadFactory): ExecutorService =
    new ThreadPoolExecutor(n, n,
      0L, TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue[Runnable],
      threadFactory)

  private[http4s] def newDaemonPool(name: String, min: Int = 4, cpuFactor: Double = 3.0, timeout: Boolean = false) = {
    val cpus = Runtime.getRuntime.availableProcessors
    val exec = new ThreadPoolExecutor(
      math.max(min, cpus), math.max(min, (cpus * cpuFactor).ceil.toInt),
      10L, TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable],
      threadFactory(i => s"${name}-$i", daemon = true))
    exec.allowCoreThreadTimeOut(timeout)
    exec
  }

  /** Servers and clients shouldn't run on a daemon.  This will go away. */
  private[http4s] val DefaultPool =
    newDaemonPool("http4s-pool")
}
