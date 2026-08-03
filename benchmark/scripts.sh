#!/bin/bash

CLIENTS=15000
RATE=150
DURATION=200

python benchmark.py \
    --clients "$CLIENTS" \
    --rate "$RATE" \
    --duration "$DURATION"