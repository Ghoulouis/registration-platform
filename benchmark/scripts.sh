#!/bin/bash

CLIENTS=20000
RATE=200
DURATION=200

python benchmark.py \
    --clients "$CLIENTS" \
    --rate "$RATE" \
    --duration "$DURATION"