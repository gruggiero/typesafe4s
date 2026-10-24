package typesafe4s.client

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.concurrent.duration.FiniteDuration

import typesafe4s.{AnswerSet, Evaluation}

/**
  * An opt-in cache in front of the service (#49): a `systemOne` call whose model, encoded state and questions match a cached one is
  * answered from the cache, making no request and spending no tokens. Set one with `config.copy(cache = Some(ResponseCache.inMemory(...)))`
  * or implement this trait over your own store.
  *
  * Only successful evaluations are stored; a failure is never cached. A key is the SHA-256 digest of the service URL and the request
  * body, so it never contains the API key, which travels only in a header. A cache that throws is treated as a miss and never fails a
  * call. A batched call caches each of its batches.
  */
trait ResponseCache {

  /**
    * The evaluation stored under `key`, if any.
    */
  def get(key: String): Option[Evaluation[AnswerSet]]

  /**
    * Stores a successful evaluation under `key`.
    */
  def put(key: String, evaluation: Evaluation[AnswerSet]): Unit
}

object ResponseCache {

  /**
    * A thread-safe in-memory cache holding at most `maxEntries` evaluations, each for at most `ttl`. When full, the least recently used
    * entry is evicted.
    */
  def inMemory(maxEntries: Int, ttl: FiniteDuration): ResponseCache =
    new InMemory(maxEntries, ttl, () => System.nanoTime())

  // the key of a request: the digest of the URL it is sent to and its body
  private[typesafe4s] def key(target: String, body: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(s"$target\n$body".getBytes(StandardCharsets.UTF_8))
    digest.map(b => f"${b & 0xff}%02x").mkString
  }

  final private[typesafe4s] class InMemory(maxEntries: Int, ttl: FiniteDuration, nanoTime: () => Long) extends ResponseCache {
    require(maxEntries > 0, s"maxEntries must be positive, got $maxEntries")
    require(ttl.length > 0, s"ttl must be positive, got $ttl")

    private val entries = new java.util.LinkedHashMap[String, (Long, Evaluation[AnswerSet])](16, 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[String, (Long, Evaluation[AnswerSet])]): Boolean = size > maxEntries
    }

    def get(key: String): Option[Evaluation[AnswerSet]] = entries.synchronized {
      Option(entries.get(key)).flatMap { case (expires, evaluation) =>
        if (nanoTime() - expires < 0) Some(evaluation)
        else { entries.remove(key); None }
      }
    }

    def put(key: String, evaluation: Evaluation[AnswerSet]): Unit = entries.synchronized {
      entries.put(key, (nanoTime() + ttl.toNanos, evaluation)); ()
    }
  }
}
