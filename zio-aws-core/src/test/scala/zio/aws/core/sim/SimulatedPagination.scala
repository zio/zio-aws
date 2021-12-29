package zio.aws.core.sim

import java.util.concurrent.{CompletableFuture, ExecutorService}

object SimulatedPagination {

  case class PaginatedRequest(input: String, token: Option[String])
  case class PaginatedResult(output: List[Char], next: Option[String])

  def simplePagination(failAfter: Option[Int], failure: Throwable)(
      request: PaginatedRequest
  )(implicit
      threadPool: ExecutorService
  ): CompletableFuture[PaginatedResult] = {
    val cf = new CompletableFuture[PaginatedResult]()

    threadPool.submit(new Runnable {
      override def run(): Unit = {
        val startIdx = request.token match {
          case Some(value) =>
            value.toInt
          case None =>
            0
        }

        if (startIdx >= failAfter.getOrElse(Int.MaxValue)) {
          cf.completeExceptionally(failure)
        } else {
          val chunk = request.input
            .substring(startIdx, Math.min(request.input.length, startIdx + 2))
            .toList
          val nextIndex = startIdx + 2
          val nextToken =
            if (nextIndex >= request.input.length) None
            else Some(nextIndex.toString)

          cf.complete(
            PaginatedResult(
              chunk,
              nextToken
            )
          )
        }
      }
    })

    cf
  }
}
