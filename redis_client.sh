#!/bin/sh
echo "select 1" >command_file
echo "ZREVRANGEBYSCORE top_tags +inf -inf WITHSCORES" >>command_file

IP_ADDRESS=$1

while true; do
    sleep 2
    redis-cli -h "$IP_ADDRESS" < test_file
done
