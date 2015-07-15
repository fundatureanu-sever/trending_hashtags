
Approach Overview
=================

Streaming algorithm:

1. Extract the hashtags from the Tweets
2. Compute hashtag frequencies per sliding window
3. Sort the (hashtag, frequency) pairs descending by frequency
4. For each RDD, take the top X from the sorted stream
5. For each RDD, update a Redis Sorted Set with the computed top X


Visualization:

The redis_client.sh (configured with the IP to which Redis binds), reads the whole Sorted Set each second.

