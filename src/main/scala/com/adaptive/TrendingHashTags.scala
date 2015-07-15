package com.adaptive;

import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.SparkContext._
import org.apache.spark.streaming.twitter._
import org.apache.spark.SparkConf
import com.twitter.finagle.redis.{TransactionalClient, Client}
import com.twitter.finagle.redis.util.StringToChannelBuffer
import com.twitter.finagle.redis.protocol.{Command, ZAdd, LTrim, ZMember, ZRemRangeByRank}
import com.twitter.finagle.redis.protocol.Reply
import com.twitter.finagle.redis.protocol.ErrorReply
import com.twitter.util.Future
import com.twitter.util.Await
import twitter4j.Status
import java.util.Properties
import java.io.FileInputStream


/**
 * Calculates popular hashtags (topics) over sliding 120 second windows from a Twitter
 * stream and updates a Redis sorted set. 
 * The stream is instantiated with credentials from a config.properties files.
 * Expected command line arguments: <Redis IP address> <twitter filters>
 *
 */
object TrendingHashTags {
  
  object CONSTANTS {
    val WINDOW_SIZE_SECS = 120
    val BATCH_DURATION : Int = 2
    val TOP_TRENDING_TAGS : Int = 10
  }
  
  def main(args: Array[String]) {
    if (args.length==0){
      System.err.println("Usage: TrendingHashTags redisIP [twitter Filters]")
      System.exit(1)
    }
    val redisIP = args(0)
    val filters = args.takeRight(args.length-1)
    
        
    val (stream, ssc) = createTwitterSparkStream(filters)
        
    val hashTags = stream.flatMap(status => extractHashTag(status))

    // get the frequency of each hashTag in the specified window
    val unsortedTagCountPairs = hashTags.map( tag => (tag, 1))
            .reduceByKeyAndWindow((a, b) => a+b, (a, b) => a-b, Seconds(CONSTANTS.WINDOW_SIZE_SECS))          
                      
    // get top X trending hashtags from the window
    val topCounts =  unsortedTagCountPairs.map{case (topic, count) => (count, topic)}
                     .transform(_.sortByKey(false))

    topCounts.foreachRDD(rdd => {
      val topList = rdd.take(CONSTANTS.TOP_TRENDING_TAGS)
      println("\nPopular topics in last 120 seconds (%s total):".format(rdd.count()))
      insertMicroBatchIntoRedis(topList, redisIP)
    })

    ssc.start()
    ssc.awaitTermination()
  }
  
  
  def extractHashTag(status : Status) : Array[String] = {
    return status.getText.split(" ").filter(_.startsWith("#"))
  }
  
  def insertMicroBatchIntoRedis(topList : Array[(Int, String)], redisIP : String) = {
        val redisClient = TransactionalClient(redisIP + ":6379")
        redisClient.select(1)
        
        var sequence: Seq[Command] = Seq(ZRemRangeByRank(StringToChannelBuffer("top_tags"), 0, -1))
        topList.foreach {         
          case (count, tag) => 
                sequence ++= Seq(ZAdd(StringToChannelBuffer("top_tags"), Seq(ZMember(count, StringToChannelBuffer(tag)))))        
        }
        println("\nCommands total: %s:".format(sequence.size))

        val result: Future[Seq[Reply]] = redisClient.transaction(sequence)
        val replies: Seq[Reply] = Await.result(result)
        
        redisClient.release()
        redisClient.quit();
  }
  
  def createTwitterSparkStream(filters: Array[String]) = {
    // Set the system properties so that Twitter4j library used by twitter stream
    // can use them to generat OAuth credentials
    val prop = new Properties()
    prop.load(new FileInputStream("config.properties"))
    
    System.setProperty("twitter4j.oauth.consumerKey", prop.getProperty("consumerKey"))
    System.setProperty("twitter4j.oauth.consumerSecret", prop.getProperty("consumerSecret"))
    System.setProperty("twitter4j.oauth.accessToken", prop.getProperty("accessToken"))
    System.setProperty("twitter4j.oauth.accessTokenSecret", prop.getProperty("accessTokenSecret"))
    
    val sparkConf = new SparkConf().setMaster("local[2]").setAppName("TrendingHashTags")  
    val ssc = new StreamingContext(sparkConf, Seconds(CONSTANTS.BATCH_DURATION))
    ssc.checkpoint("/tmp")
    
    val stream = TwitterUtils.createStream(ssc, None, filters)
    (stream, ssc)
  }
    
}

