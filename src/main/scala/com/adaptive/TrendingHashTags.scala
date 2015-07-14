package com.adaptive;

import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.SparkContext._
import org.apache.spark.streaming.twitter._
import org.apache.spark.SparkConf
import com.twitter.finagle.redis.{TransactionalClient, Client}
import com.twitter.finagle.redis.util.StringToChannelBuffer
import com.twitter.finagle.redis.protocol.{Command, ZIncrBy}
import com.twitter.finagle.redis.protocol.Reply
import com.twitter.finagle.redis.protocol.ErrorReply
import com.twitter.util.Future
import com.twitter.util.Await
import twitter4j.Status

/**
 * Calculates popular hashtags (topics) over sliding 10 and 60 second windows from a Twitter
 * stream. The stream is instantiated with credentials and optionally filters supplied by the
 * command line arguments.
 *
 * Run this on your local machine as
 *
 */
object TrendingHashTags {
  
  object CONSTANTS {
    val WINDOW_SIZE_SECS = 120
    val BATCH_DURATION = 2
  }
  
  def extractHashTag(status : Status) : Array[String] = {
    return status.getText.split(" ").filter(_.startsWith("#"))
  }
  
  def insertMicroBatchIntoRedis(topList : Array[(Int, String)]) = {
        val redisClient = TransactionalClient("10.51.78.48:6379")
        redisClient.select(0)
        
        var sequence: Seq[Command] = Seq()
        topList.foreach { 
          //case (count, tag) => println("%s (%s tweets)".format(tag, count))        
          case (count, tag) => 
                sequence ++= Seq(ZIncrBy(StringToChannelBuffer("global_trends"), count, StringToChannelBuffer(tag)))        
        }
        println("\nCommands total: %s:".format(sequence.size))

        val result: Future[Seq[Reply]] = redisClient.transaction(sequence)
        val replies: Seq[Reply] = Await.result(result)
        
        redisClient.release()
        redisClient.quit();
  }
  
  def main(args: Array[String]) {
    if (args.length < 4) {
      System.err.println("Usage: TrendingHashTags <consumer key> <consumer secret> " +
        "<access token> <access token secret> [<filters>]")
      System.exit(1)
    }

    StreamingExamples.setStreamingLogLevels()

    val Array(consumerKey, consumerSecret, accessToken, accessTokenSecret) = args.take(4)
    val filters = args.takeRight(args.length - 4)

    // Set the system properties so that Twitter4j library used by twitter stream
    // can use them to generat OAuth credentials
    System.setProperty("twitter4j.oauth.consumerKey", consumerKey)
    System.setProperty("twitter4j.oauth.consumerSecret", consumerSecret)
    System.setProperty("twitter4j.oauth.accessToken", accessToken)
    System.setProperty("twitter4j.oauth.accessTokenSecret", accessTokenSecret)

    val sparkConf = new SparkConf().setMaster("local[10]").setAppName("TrendingHashTags")
    
    val ssc = new StreamingContext(sparkConf, Seconds(CONSTANTS.BATCH_DURATION))
    val stream = TwitterUtils.createStream(ssc, None, filters)
    
    

    /*val topCounts60 = hashTags.map((_, 1)).reduceByKeyAndWindow(_ + _, Seconds(60))
                     .map{case (topic, count) => (count, topic)}
                     .transform(_.sortByKey(false))
    // Print popular hashtags
    topCounts60.foreachRDD(rdd => {
      val topList = rdd.take(10)
      println("\nPopular topics in last 60 seconds (%s total):".format(rdd.count()))
      topList.foreach{
        case (count, tag) => println("%s (%s tweets)".format(tag, count))
        
      }
    })*/
    
    val hashTags = stream.flatMap(status => extractHashTag(status))

    val unsortedTagCountPairs = hashTags.map( tag => (tag, 1))
            .reduceByKeyAndWindow((a, b) => a+b, (a, b) => a-b, Seconds(CONSTANTS.WINDOW_SIZE_SECS))
            //.countByValueAndWindow(Seconds(CONSTANTS.WINDOW_SIZE_SECS), Seconds(CONSTANTS.BATCH_DURATION))          
                      
    val topCounts2sec =  unsortedTagCountPairs.map{case (topic, count) => (count, topic)}
                     .transform(_.sortByKey(false))

    topCounts2sec.foreachRDD(rdd => {
      val topList = rdd.take(10)
      println("\nPopular topics in last 2 seconds (%s total):".format(rdd.count()))
      insertMicroBatchIntoRedis(topList)
    })

    ssc.start()
    ssc.awaitTermination()
  }
}
// scalastyle:on println
