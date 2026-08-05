#!/bin/bash

CLIENTS=15000
RATE=100
DURATION=100
VALIDITY_PERIOD_SECONDS=10 # 10 second
BUCKET_WHEEL=10000
python benchmark.py \
    --clients "$CLIENTS" \
    --rate "$RATE" \
    --duration "$DURATION" \
    --validity-period-seconds "$VALIDITY_PERIOD_SECONDS" \
    --timer-ticks-per-wheel "$BUCKET_WHEEL"