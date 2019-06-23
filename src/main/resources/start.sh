#!/bin/sh
export BASE_PATH=/surebet-finder/betway-scraper
# kill eventually instances
count=$(ps aux | grep ${BASE_PATH}/betway-scraper.jar | grep -v grep | wc -l)
if [ "$count" -gt "0" ]; then
  ps aux | grep ${BASE_PATH}/betway-scraper.jar | grep -v grep | awk '{print $2}' | xargs kill -9
fi
# move old log file, then mantain last 10 files more recent
cd ${BASE_PATH}/logs
ls -tp | grep -v '/$' | tail -n +11 | xargs -I {} rm -- {}
if [ -f ${BASE_PATH}/scraper.log ]; then
  mv ${BASE_PATH}/scraper.log ${BASE_PATH}/logs/betway_$(date +"%Y-%m-%d_%H-%M").log
fi
cd ${BASE_PATH}/logs | ls -tp | grep -v '/$' | tail -n +11 | xargs -I {} rm -- {}
# run application
cd ${BASE_PATH}/
nohup java -jar betway-scraper.jar </dev/null > scraper.log 2>&1