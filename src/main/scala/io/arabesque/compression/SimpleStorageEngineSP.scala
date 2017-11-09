package io.arabesque.compression

import java.io.{ByteArrayOutputStream, DataOutput, DataOutputStream, OutputStream}

import io.arabesque.conf.SparkConfiguration
import io.arabesque.embedding.Embedding
import io.arabesque.pattern.Pattern
import org.apache.spark.Accumulator
import org.apache.spark.broadcast.Broadcast

import scala.collection.mutable.Map
import scala.collection.JavaConversions._

/**
  * Created by ehussein on 7/16/17.
  */
case class SimpleStorageEngineSP [E <: Embedding]
  (override val partitionId: Int, superstep: Int, accums: Map[String,Accumulator[_]],
   // TODO do not broadcast if user's code does not requires it
   previousAggregationsBc: Broadcast[_])
  extends SimpleStorageEngine[E, SinglePatternSimpleStorage, SinglePatternSimpleStorageStash, SimpleStorageEngineSP[E]] {

  // stashes
  nextEmbeddingStash = new SinglePatternSimpleStorageStash

  def saveReports() = {
    partitionReport.endTime = System.currentTimeMillis()
    if(generateReports) {
      partitionReport.partitionId = this.partitionId
      partitionReport.superstep = this.superstep
      partitionReport.storageReports = storageReports.toArray
      partitionReport.saveReport(reportsFilePath)
    }
  }

  /**
    * Returns a new execution engine from this with the aggregations/computation
    * variables updated (immutability)
    *
    * @param aggregationsBc broadcast variable with aggregations
    * @return the new execution engine, ready for flushing
    */
  def withNewAggregations(aggregationsBc: Broadcast[_]) : SimpleStorageEngineSP[E] = {

    // we first get a copy of the this execution engine, with previous
    // aggregations updated
    val execEngine = this.copy [E] (
      previousAggregationsBc = aggregationsBc,
      accums = accums)

    // set next stash with odags
    execEngine.nextEmbeddingStash = nextEmbeddingStash

    execEngine
  }

  override def flush: Iterator[(_,_)] = configuration.getOdagFlushMethod match {
    case SparkConfiguration.FLUSH_BY_PATTERN => flushByPattern
    case SparkConfiguration.FLUSH_BY_ENTRIES => flushByEntries
    case SparkConfiguration.FLUSH_BY_PARTS =>   flushByParts
  }

  /**
    * Naively flushes outbound odags.
    * We assume that this execEngine is ready to
    * do *aggregationFilter*, i.e., this execution engine was generated by
    * [[withNewAggregations]].
    *
    * @return iterator of pairs of (pattern, odag)
    */
  private def flushByPattern: Iterator[(Pattern,SinglePatternSimpleStorage)]  = {
    // consume content in *nextEmbeddingStash*
    for (storage <- nextEmbeddingStash.getEzips().iterator
         if computation.aggregationFilter (storage.getPattern)
    )
      yield (storage.getPattern, storage)
  }

  /**
    * Flushes outbound odags in parts, i.e., with single domain entries per odag
    * We assume that this execEngine is ready to
    * do *aggregationFilter*, i.e., this execution engine was generated by
    * [[withNewAggregations]].
    *
    *  @return iterator of pairs of ((pattern,domainId,wordId), odag_with_one_entry)
    */
  private def flushByEntries: Iterator[((Pattern,Int,Int), SinglePatternSimpleStorage)] = {

    /**
      * Iterator that split a big BasicODAG into small ODAGs containing only one entry
      * of the original. Thus, keyed by (pattern, domainId, wordId)
      */
    class ODAGPartsIterator(storage: SinglePatternSimpleStorage) extends Iterator[((Pattern,Int,Int),SinglePatternSimpleStorage)] {
      val domainIterator = storage.getStorage.getDomainEntries.iterator
      var domainId:Int = -1
      //var currEntriesIterator: Option[Iterator[(Integer, java.lang.Boolean)]] = None
      var currEntriesIterator: Option[Iterator[(Integer)]] = None

      val reusableOdag = new SinglePatternSimpleStorage(storage.getPattern, storage.getNumberOfDomains())

      @scala.annotation.tailrec
      private def hasNextRec: Boolean = currEntriesIterator match {
        case None =>
          domainIterator.hasNext
        case Some(entriesIterator) if entriesIterator.isEmpty =>
          currEntriesIterator = None
          hasNextRec
        case Some(entriesIterator) =>
          entriesIterator.hasNext
      }

      override def hasNext: Boolean = hasNextRec

      @scala.annotation.tailrec
      private def nextRec: ((Pattern,Int,Int),SinglePatternSimpleStorage) = currEntriesIterator match {
        case None => // set next domain and recursive call
          currEntriesIterator = Some(domainIterator.next.iterator)
          domainId += 1
          nextRec

        case Some(entriesIterator) => // format domain entry as new BasicODAG
          val newOdag = new SinglePatternSimpleStorage(storage.getPattern, storage.getNumberOfDomains())
          //val (wordId, entry) = entriesIterator.next
          val wordId = entriesIterator.next
          val domainEntries = newOdag.getStorage.getDomainEntries

          //*
          domainEntries(domainId).synchronized {
            // for map based storage
            //domainEntries(domainId).put(wordId, entry)
            // for list based storage
            domainEntries(domainId).add(wordId.intValue())
          }
          //*/
          //domainEntries.get(domainId).put(wordId, entry)

          ((newOdag.getPattern,domainId,wordId.intValue), newOdag)
      }

      override def next = nextRec
    }

    // filter and flush
    nextEmbeddingStash.getEzips().iterator.
      filter(storage => computation.aggregationFilter (storage.getPattern)).
      flatMap (new ODAGPartsIterator(_))
  }

  /**
    * Flushes outbound odags by chunks of bytes
    * We assume that this execEngine is ready to
    * do *aggregationFilter*, i.e., this execution engine was generated by
    * [[withNewAggregations]].
    *
    * @return iterator of pairs ((pattern,partId), bytes)
    */
  private def flushByParts: Iterator[((Pattern,Int),Array[Byte])] = {

    val numPartitions = getNumberPartitions()
    val outputs = Array.fill[ByteArrayOutputStream](numPartitions)(new ByteArrayOutputStream())
    def createDataOutput(output: OutputStream): DataOutput = new DataOutputStream(output)
    val dataOutputs = outputs.map (output => createDataOutput(output))
    val hasContent = new Array[Boolean](numPartitions)

    nextEmbeddingStash.getEzips().iterator.
      filter (storage => computation.aggregationFilter(storage.getPattern)).
      flatMap { storage =>

        // reset aux structures
        var i = 0
        while (i < numPartitions) {
          outputs(i).reset
          hasContent(i) = false
          i += 1
        }

        // this method writes odag content into DataOutputs in parts
        storage.writeInParts (dataOutputs, hasContent)

        // attach to each byte array the corresponding key, i.e., (pattern, partId)
        for (partId <- 0 until numPartitions if hasContent(partId))
          yield ((storage.getPattern, partId), outputs(partId).toByteArray)
      }
  }

}