#!/bin/bash

CLIENTS=200000
RATE=200
DURATION=2000
VALIDITY_PERIOD_SECONDS=360 # 1hour
BUCKET_WHEEL=100000
python benchmark.py \
    --clients "$CLIENTS" \
    --rate "$RATE" \
    --duration "$DURATION" \
    --validity-period-seconds "$VALIDITY_PERIOD_SECONDS" \
    --timer-ticks-per-wheel "$BUCKET_WHEEL"
