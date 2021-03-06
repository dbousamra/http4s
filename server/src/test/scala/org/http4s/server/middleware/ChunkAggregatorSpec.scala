package org.http4s.server.middleware

import cats.data.NonEmptyList
import cats.instances.int._
import cats.instances.vector._
import cats.syntax.foldable._
import fs2._
import org.http4s._
import org.http4s.dsl._
import org.http4s.headers._
import org.http4s.util.chunk._
import org.scalacheck._
import org.specs2.matcher.MatchResult

class ChunkAggregatorSpec extends Http4sSpec {

  val transferCodingGen: Gen[Seq[TransferCoding]] =
    Gen.someOf(Seq(TransferCoding.compress, TransferCoding.deflate, TransferCoding.gzip, TransferCoding.identity))
  implicit val transferCodingArbitrary = Arbitrary(transferCodingGen.map(_.toList))

  "ChunkAggregator" should {
    def checkResponse(body: EntityBody, transferCodings: List[TransferCoding])(responseCheck: Response => MatchResult[Any]): MatchResult[Any] = {
      val service: HttpService = HttpService.lift { _ =>
        Ok()
          .putHeaders(`Transfer-Encoding`(NonEmptyList(TransferCoding.chunked, transferCodings)))
          .withBody(body)
      }
      ChunkAggregator(service).run(Request()).unsafeValue() must beSome.like {
        case maybeResponse: MaybeResponse =>
          val response = maybeResponse.orNotFound
          response.status must_== Ok
          responseCheck(response)
      }
    }

    "handle an empty body" in {
      checkResponse(EmptyBody, Nil) { response =>
        response.contentLength must_=== None
        response.body.runLog.unsafeValue() must_=== Some(Vector.empty)
      }
    }

    "handle a Pass" in {
      val service: HttpService = HttpService.lift(_ => Pass.now)
      ChunkAggregator(service).run(Request()).unsafeValue() must beSome(Pass)
    }

    "handle chunks" in {
      prop { (chunks: NonEmptyList[Chunk[Byte]], transferCodings: List[TransferCoding]) =>
        val totalChunksSize = chunks.foldMap(_.size)
        checkResponse(chunks.map(Stream.chunk[Task, Byte]).reduceLeft(_ ++ _), transferCodings) { response =>
          if (totalChunksSize > 0) {
            response.contentLength must_=== Some(totalChunksSize.toLong)
            response.headers.get(`Transfer-Encoding`).map(_.values) must_=== NonEmptyList.fromList(transferCodings)
          }
          response.body.runLog.unsafeValue() must beSome(chunks.foldMap(_.toVector))
        }
      }
    }
  }

}
