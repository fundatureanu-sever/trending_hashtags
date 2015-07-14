package com.adaptive;

import com.twitter.finagle.redis.{TransactionalClient, Client}
import com.twitter.finagle.redis.util.StringToChannelBuffer
import com.twitter.finagle.redis.protocol.{Command, ZIncrBy}

object TestRedisClient {
  def main(args: Array[String]) {
    val redisClient = TransactionalClient( "10.51.78.48:6379" )
    redisClient.select(0)
    redisClient.set(StringToChannelBuffer("testKey"), StringToChannelBuffer("testValue3"))
    
    println("Inserted")
    redisClient.release()
    redisClient.quit()
  }
}